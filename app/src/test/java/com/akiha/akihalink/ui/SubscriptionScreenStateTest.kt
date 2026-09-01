package com.akiha.akihalink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionScreenStateTest {
    @Test
    fun subscriptionDateUsesMonthAndDayOnly() {
        val formatted = formatSubscriptionDate(1_700_000_000_000L)

        assertTrue(formatted.matches(Regex("\\d{2}-\\d{2}")))
        assertEquals(5, formatted.length)
    }

    @Test
    fun subscriptionDateAcceptsSecondTimestamps() {
        assertEquals(
            formatSubscriptionDate(1_700_000_000_000L),
            formatSubscriptionDate(1_700_000_000L),
        )
    }
}
