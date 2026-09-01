package com.akiha.akihalink.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTraceTest {
    @Test
    fun generatedContextIsAnonymousAndValid() {
        val first = PerformanceTrace.create()
        val second = PerformanceTrace.create()
        assertTrue(TraceContext.ID_PATTERN.matches(first.id))
        assertEquals(16, first.id.length)
        assertNotEquals(first.id, second.id)
        assertTrue(first.cookie >= 0)
    }

    @Test
    fun rejectsUnsafeTraceIds() {
        assertThrows(IllegalArgumentException::class.java) {
            TraceContext("node.example:443", 1)
        }
    }

}
