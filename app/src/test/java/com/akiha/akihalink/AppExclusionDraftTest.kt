package com.akiha.akihalink

import org.junit.Assert.assertEquals
import org.junit.Test

class AppExclusionDraftTest {
    @Test
    fun firstToggleStartsFromPersistedSelection() {
        val result = toggleExclusionDraft(
            persisted = setOf(10_101),
            draft = null,
            uid = 10_102,
        )

        assertEquals(setOf(10_101, 10_102), result)
    }

    @Test
    fun togglingSameUidTwiceReturnsToPersistedSelection() {
        val persisted = setOf(10_101)
        val first = toggleExclusionDraft(persisted, draft = null, uid = 10_102)
        val second = toggleExclusionDraft(persisted, draft = first, uid = 10_102)

        assertEquals(persisted, second)
    }

}
