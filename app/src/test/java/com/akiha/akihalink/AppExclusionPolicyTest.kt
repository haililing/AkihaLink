package com.akiha.akihalink

import com.akiha.akihalink.data.ExcludedAppEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExclusionPolicyTest {
    @Test
    fun doesNotExcludeAnotherPackageThatReusesAStoredUid() {
        val result = resolveExcludedApps(
            listOf(exclusion(uid = 10_101, packageName = "dev.example.old", signer = "aa")),
            listOf(installed(uid = 10_101, packageName = "dev.example.new", signer = "bb")),
        )

        assertTrue(result.effectiveUids.isEmpty())
        assertEquals(1, result.stale.size)
    }

    @Test
    fun doesNotExcludeAReinstalledPackageWithADifferentSigner() {
        val result = resolveExcludedApps(
            listOf(exclusion(uid = 10_101, packageName = "dev.example.app", signer = "aa")),
            listOf(installed(uid = 10_101, packageName = "dev.example.app", signer = "bb")),
        )

        assertTrue(result.effectiveUids.isEmpty())
        assertEquals(1, result.stale.size)
    }

    @Test
    fun followsTheSameSignedPackageWhenItsUidChanges() {
        val result = resolveExcludedApps(
            listOf(exclusion(uid = 10_101, packageName = "dev.example.app", signer = "aa")),
            listOf(installed(uid = 10_205, packageName = "dev.example.app", signer = "aa")),
        )

        assertEquals(setOf(10_205), result.effectiveUids)
        assertEquals(10_205, result.matched.single().uid)
    }

    @Test
    fun acceptsAValidSigningCertificateRotation() {
        val result = resolveExcludedApps(
            listOf(exclusion(uid = 10_101, packageName = "dev.example.app", signer = "aa")),
            listOf(
                installed(
                    uid = 10_101,
                    packageName = "dev.example.app",
                    signer = "bb",
                    acceptedSigners = setOf("aa", "bb"),
                ),
            ),
        )

        assertEquals(setOf(10_101), result.effectiveUids)
        assertEquals("bb", result.matched.single().signingCertificateSha256)
    }

    @Test
    fun selectingASharedUidStoresEveryPackageIdentity() {
        val packages = listOf(
            installed(10_101, "dev.example.one", "aa"),
            installed(10_101, "dev.example.two", "bb"),
        )

        val stored = exclusionsForUids(setOf(10_101), packages)
        val resolved = resolveExcludedApps(stored, packages)
        val grouped = groupInstalledApps(packages, resolved.effectiveUids)

        assertEquals(setOf("dev.example.one", "dev.example.two"), stored.map { it.packageName }.toSet())
        assertEquals(setOf(10_101), resolved.effectiveUids)
        assertEquals(2, grouped.single().packageCount)
        assertEquals("dev.example.one", grouped.single().iconPackageName)
        assertTrue(grouped.single().excluded)
    }

    @Test
    fun quarantinesLegacyRowsWithoutASigningIdentity() {
        val result = resolveExcludedApps(
            listOf(exclusion(uid = 10_101, packageName = "dev.example.app", signer = "")),
            listOf(installed(uid = 10_101, packageName = "dev.example.app", signer = "aa")),
        )

        assertTrue(result.effectiveUids.isEmpty())
        assertEquals(1, result.stale.size)
    }

    private fun exclusion(uid: Int, packageName: String, signer: String) = ExcludedAppEntity(
        userId = uid / ANDROID_USER_UID_RANGE,
        packageName = packageName,
        signingCertificateSha256 = signer,
        uid = uid,
        label = packageName,
    )

    private fun installed(
        uid: Int,
        packageName: String,
        signer: String,
        acceptedSigners: Set<String> = setOf(signer),
    ) = InstalledPackageIdentity(
        uid = uid,
        userId = uid / ANDROID_USER_UID_RANGE,
        packageName = packageName,
        label = packageName,
        signingCertificateSha256 = signer,
        acceptedSigningCertificateSha256 = acceptedSigners,
        isSystem = false,
    )
}
