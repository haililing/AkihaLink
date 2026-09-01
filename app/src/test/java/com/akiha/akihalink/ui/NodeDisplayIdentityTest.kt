package com.akiha.akihalink.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeDisplayIdentityTest {
    @Test
    fun flagTakesPriorityAndProducesCountryCode() {
        assertEquals("🇯🇵 JP", nodeDisplayIdentity("高速 🇯🇵 东京 01").label)
    }

    @Test
    fun localizedNamesAndCodesAreNormalized() {
        assertEquals("🇸🇬 SG", nodeDisplayIdentity("新加坡中转").label)
        assertEquals("🇭🇰 HK", nodeDisplayIdentity("HK Premium").label)
        assertEquals("🇬🇧 UK", nodeDisplayIdentity("London UK 02").label)
    }

    @Test
    fun unknownCountryHasStableFallback() {
        assertEquals("🌐 UN", nodeDisplayIdentity("Premium relay 01").label)
    }

    @Test
    fun displayNameKeepsOnlyCountryRouteAndMultiplier() {
        assertEquals("日本|高速|0.5x", nodeDisplayName("🇯🇵 东京 高速 0.5x 01"))
        assertEquals("新加坡|专线|2x", nodeDisplayName("新加坡 IEPL Premium 2× 03"))
        assertEquals("香港|专线|1.5x", nodeDisplayName("HK 高速 专线 1.5倍 VIP"))

        val details = nodeDisplayDetails("新加坡 IEPL Premium 2× 03")
        assertEquals("新加坡", details.identity.countryName)
        assertEquals(listOf("专线", "2x"), details.attributes)
    }

    @Test
    fun displayNameOmitsUnavailableKeywords() {
        assertEquals("美国", nodeDisplayName("洛杉矶普通节点 01"))
        assertEquals("其他|3x", nodeDisplayName("Premium relay 3X"))
    }
}
