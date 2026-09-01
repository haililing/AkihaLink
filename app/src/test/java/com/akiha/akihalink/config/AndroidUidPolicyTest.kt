package com.akiha.akihalink.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUidPolicyTest {
    @Test
    fun coversSystemAppsSdkSandboxesAndIsolatedProcessesForEveryUser() {
        val policy = AndroidUidPolicy.build(
            androidUserIds = setOf(10, 0),
            excludedUids = setOf(10_119, 1_010_321),
            appUid = 10_456,
        )

        assertEquals(listOf(1_051, 1_073), policy.includeUids)
        assertEquals(
            listOf(
                "10000:29999",
                "90000:99999",
                "1010000:1029999",
                "1090000:1099999",
            ),
            policy.includeUidRanges,
        )
        assertEquals(
            listOf(10_119, 10_456, 20_119, 20_456, 1_010_321, 1_020_321),
            policy.excludeUids,
        )
        assertEquals(2, policy.summary.androidUserCount)
        assertEquals(6, policy.summary.effectiveExcludedUidCount)
        assertEquals(3, policy.summary.sdkSandboxDerivedExclusionCount)
        assertTrue(policy.summary.sdkSandboxExclusionsDerived)
        assertTrue(policy.summary.isolatedProcessesAlwaysProxied)
    }

    @Test
    fun isolatedUidsCannotBeExcludedAndExistingSandboxExclusionsAreNotDuplicated() {
        val policy = AndroidUidPolicy.build(
            androidUserIds = setOf(0),
            excludedUids = setOf(10_119, 20_119, 90_123, 99_999),
            appUid = -1,
        )

        assertEquals(listOf(10_119, 20_119), policy.excludeUids)
        assertEquals(0, policy.summary.sdkSandboxDerivedExclusionCount)
        assertFalse(policy.excludeUids.any { it in 90_000..99_999 })
    }

    @Test
    fun resetVerificationUsesOnlyPrimaryAndDerivedSandboxUids() {
        val effective = AndroidUidPolicy.effectiveExclusionUids(
            setOf(10_321, 110_321, 90_321, 120_321),
        )

        assertEquals(setOf(10_321, 20_321, 110_321, 120_321), effective)
        assertFalse(90_321 in effective)
    }

    @Test
    fun emptyUserSetFallsBackToMainUserWithoutDuplicateRanges() {
        val policy = AndroidUidPolicy.build(
            androidUserIds = emptySet(),
            excludedUids = emptySet(),
            appUid = -1,
        )

        assertEquals(listOf("10000:29999", "90000:99999"), policy.includeUidRanges)
        assertEquals(1, policy.summary.androidUserCount)
        assertEquals(policy.includeUidRanges.distinct(), policy.includeUidRanges)
    }

    @Test
    fun rejectsOutOfRangeAndroidUserIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidUidPolicy.build(
                androidUserIds = setOf(1_000),
                excludedUids = emptySet(),
                appUid = -1,
            )
        }
    }
}
