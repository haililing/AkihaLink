package com.akiha.akihalink

import com.akiha.akihalink.data.ExcludedAppEntity

data class InstalledPackageIdentity(
    val uid: Int,
    val userId: Int,
    val packageName: String,
    val label: String,
    val signingCertificateSha256: String,
    val acceptedSigningCertificateSha256: Set<String>,
    val isSystem: Boolean,
)

data class InstalledApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val excluded: Boolean,
    val isSystem: Boolean = false,
    val userId: Int = uid / ANDROID_USER_UID_RANGE,
    val packageCount: Int = 1,
    val iconPackageName: String = packageName,
)

data class ExclusionResolution(
    val effectiveUids: Set<Int>,
    val matched: List<ExcludedAppEntity>,
    val stale: List<ExcludedAppEntity>,
)

internal fun resolveExcludedApps(
    exclusions: List<ExcludedAppEntity>,
    installedPackages: List<InstalledPackageIdentity>,
): ExclusionResolution {
    val installedByPackage = installedPackages.associateBy { it.userId to it.packageName }
    val matched = mutableListOf<ExcludedAppEntity>()
    val stale = mutableListOf<ExcludedAppEntity>()
    exclusions.forEach { exclusion ->
        val installed = installedByPackage[exclusion.userId to exclusion.packageName]
        val storedSigners = exclusion.signingCertificateSha256
            .split(SIGNER_SEPARATOR)
            .filter(String::isNotBlank)
            .toSet()
        if (installed != null && storedSigners.isNotEmpty() &&
            storedSigners.all(installed.acceptedSigningCertificateSha256::contains)
        ) {
            matched += exclusion.copy(
                uid = installed.uid,
                label = installed.label,
                signingCertificateSha256 = installed.signingCertificateSha256,
            )
        } else {
            stale += exclusion
        }
    }
    return ExclusionResolution(
        effectiveUids = matched.mapTo(sortedSetOf()) { it.uid },
        matched = matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
        stale = stale,
    )
}

internal fun exclusionsForUids(
    selectedUids: Set<Int>,
    installedPackages: List<InstalledPackageIdentity>,
): List<ExcludedAppEntity> = installedPackages
    .asSequence()
    .filter { it.uid in selectedUids }
    .map { installed ->
        ExcludedAppEntity(
            userId = installed.userId,
            packageName = installed.packageName,
            signingCertificateSha256 = installed.signingCertificateSha256,
            uid = installed.uid,
            label = installed.label,
        )
    }
    .distinctBy { it.userId to it.packageName }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    .toList()

internal fun groupInstalledApps(
    installedPackages: List<InstalledPackageIdentity>,
    excludedUids: Set<Int>,
): List<InstalledApp> = installedPackages
    .groupBy(InstalledPackageIdentity::uid)
    .map { (uid, packages) ->
        val sorted = packages.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, InstalledPackageIdentity::label)
                .thenBy(InstalledPackageIdentity::packageName),
        )
        val primary = sorted.first()
        InstalledApp(
            uid = uid,
            userId = primary.userId,
            packageName = sorted.joinToString(" · ") { it.packageName },
            label = if (sorted.size == 1) primary.label else "${primary.label} 等 ${sorted.size} 个应用",
            excluded = uid in excludedUids,
            isSystem = sorted.any(InstalledPackageIdentity::isSystem),
            packageCount = sorted.size,
            iconPackageName = primary.packageName,
        )
    }

internal const val ANDROID_USER_UID_RANGE = 100_000
internal const val SIGNER_SEPARATOR = ":"
