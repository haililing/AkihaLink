package com.akiha.akihalink

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserManager
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidInstalledAppCatalog(context: Context) {
    private val context = context.applicationContext
    private val packageManager = this.context.packageManager

    suspend fun load(): List<InstalledPackageIdentity> = withContext(Dispatchers.IO) {
        val applications = linkedMapOf<Pair<Int, String>, ApplicationInfo>()
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .forEach { info -> applications[userId(info.uid) to info.packageName] = info }

        val currentUser = Process.myUserHandle()
        val userManager = context.getSystemService(UserManager::class.java)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        userManager.userProfiles.asSequence()
            .filter { it != currentUser }
            .forEach { profile ->
                runCatching { launcherApps.getActivityList(null, profile) }
                    .getOrDefault(emptyList())
                    .asSequence()
                    .map { it.applicationInfo }
                    .forEach { info -> applications[userId(info.uid) to info.packageName] = info }
            }

        applications.values.mapNotNull(::identityFor).sortedWith(
            compareBy<InstalledPackageIdentity> { it.userId }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy { it.packageName },
        )
    }

    private fun identityFor(info: ApplicationInfo): InstalledPackageIdentity? {
        if (info.uid < 10_000 || info.uid == context.applicationInfo.uid) return null
        val signingInfo = runCatching {
            packageManager.getPackageInfo(
                info.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            ).signingInfo
        }.getOrNull() ?: return null
        val currentSigners = signingInfo.apkContentsSigners
            .map { sha256(it.toByteArray()) }
            .toSortedSet()
        if (currentSigners.isEmpty()) return null
        val acceptedSigners = (if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners.asSequence()
        } else {
            signingInfo.signingCertificateHistory.asSequence()
        }).map { sha256(it.toByteArray()) }.toSet()
        return InstalledPackageIdentity(
            uid = info.uid,
            userId = userId(info.uid),
            packageName = info.packageName,
            label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName),
            signingCertificateSha256 = currentSigners.joinToString(SIGNER_SEPARATOR),
            acceptedSigningCertificateSha256 = acceptedSigners,
            isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
        )
    }

    private fun userId(uid: Int): Int = uid / ANDROID_USER_UID_RANGE

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
