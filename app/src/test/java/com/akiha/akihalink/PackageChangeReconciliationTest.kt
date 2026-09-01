package com.akiha.akihalink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageChangeReconciliationTest {
    @Test
    fun coalescesDuplicateEventsAndAdvancesRevision() {
        val first = mergePendingPackageChange(
            PendingPackageReconciliation(),
            uid = 10_123,
            packageName = "example.app",
            reconcileAll = false,
        )
        val duplicate = mergePendingPackageChange(
            first,
            uid = 10_123,
            packageName = "example.app",
            reconcileAll = false,
        )

        assertEquals(2L, duplicate.revision)
        assertEquals(listOf(PendingPackageChange(10_123, "example.app")), duplicate.changes)
        assertFalse(duplicate.reconcileAll)
    }

    @Test
    fun fullOrOverflowReconciliationSupersedesIndividualEvents() {
        val first = mergePendingPackageChange(
            PendingPackageReconciliation(),
            uid = 10_123,
            packageName = "example.app",
            reconcileAll = false,
        )
        val full = mergePendingPackageChange(first, 10_124, "other.app", reconcileAll = true)
        assertTrue(full.reconcileAll)
        assertTrue(full.changes.isEmpty())

        val overflow = mergePendingPackageChange(
            first,
            uid = 10_124,
            packageName = "other.app",
            reconcileAll = false,
            maxPendingChanges = 1,
        )
        assertTrue(overflow.reconcileAll)
        assertTrue(overflow.changes.isEmpty())
    }
}
