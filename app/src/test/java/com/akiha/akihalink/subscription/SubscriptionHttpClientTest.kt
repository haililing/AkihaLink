package com.akiha.akihalink.subscription

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionHttpClientTest {
    @Test
    fun rejectsCleartextAndInvalidSubscriptionUrlsBeforeConnecting() {
        val client = SubscriptionHttpClient()

        listOf(
            "http://127.0.0.1:9090/version",
            "http://subscription.example.com/list",
            "not a url",
        ).forEach { url ->
            assertThrows(SubscriptionParseException::class.java) {
                runBlocking { client.download(url) }
            }
        }
    }

    @Test
    fun parsesSubscriptionUserInfoTrafficAndExpiration() {
        val info = parseSubscriptionUserInfo(
            "download=2147483648; upload=1073741824; total=107374182400; " +
                "start=1767225600; expire=1798761600; ignored=value",
        )

        assertEquals(1_073_741_824L, info?.uploadBytes)
        assertEquals(2_147_483_648L, info?.downloadBytes)
        assertEquals(107_374_182_400L, info?.totalBytes)
        assertEquals(1_767_225_600_000L, info?.startedAt)
        assertEquals(1_798_761_600_000L, info?.expiresAt)
        assertTrue(info?.expirationProvided == true)
    }

    @Test
    fun parsesProviderStartAliasesAndTimestampFormats() {
        assertEquals(
            1_767_225_600_000L,
            parseSubscriptionUserInfo("purchase-time=1767225600000")?.startedAt,
        )
        assertEquals(
            1_767_225_600_000L,
            parseSubscriptionUserInfo("subscription-start-date=2026-01-01T00:00:00Z")?.startedAt,
        )
        assertEquals(
            1_767_225_600_000L,
            parseSubscriptionUserInfo("created_at=1767225600")?.startedAt,
        )
    }

    @Test
    fun readsExtendedMetadataFromSubscriptionComments() {
        val content = """
            //profile-title: Akiha
            //subscription-userinfo: upload=1; download=2; total=10; expire=1798761600
            # subscription-start-time: 2026-01-01T00:00:00Z
            proxies: []
        """.trimIndent().toByteArray()

        val info = mergeSubscriptionUserInfo(
            primary = null,
            fallback = parseSubscriptionUserInfoFromContent(content),
            supplementalStartedAt = parseSubscriptionStartedAtFromContent(content),
        )

        assertEquals(1L, info?.uploadBytes)
        assertEquals(2L, info?.downloadBytes)
        assertEquals(10L, info?.totalBytes)
        assertEquals(1_767_225_600_000L, info?.startedAt)
        assertEquals(1_798_761_600_000L, info?.expiresAt)
    }

    @Test
    fun keepsResponseHeaderMetadataAheadOfCommentFallbacks() {
        val header = parseSubscriptionUserInfo("upload=100; download=200; total=1000; expire=0")
        val comment = parseSubscriptionUserInfo(
            "upload=1; download=2; total=10; start_date=2026-01-01T00:00:00Z; expire=1798761600",
        )

        val merged = mergeSubscriptionUserInfo(header, comment)

        assertEquals(100L, merged?.uploadBytes)
        assertEquals(200L, merged?.downloadBytes)
        assertEquals(1_767_225_600_000L, merged?.startedAt)
        assertEquals(0L, merged?.expiresAt)
        assertTrue(merged?.expirationProvided == true)
    }

    @Test
    fun treatsZeroExpirationAsExplicitlyUnlimited() {
        val info = parseSubscriptionUserInfo("upload=0; download=0; total=0; expire=0")

        assertEquals(0L, info?.expiresAt)
        assertTrue(info?.expirationProvided == true)
    }

    @Test
    fun ignoresHeadersWithoutRecognizedUserInfoFields() {
        assertNull(parseSubscriptionUserInfo("profile-title=Akiha; interval=24"))
    }

    @Test
    fun parsesPositiveProfileUpdateIntervalAsHours() {
        assertEquals(24L, parseProfileUpdateInterval(" 24 "))
        assertNull(parseProfileUpdateInterval("0"))
        assertNull(parseProfileUpdateInterval("daily"))
        assertNull(parseProfileUpdateInterval(null))
    }

    @Test
    fun usesAProviderCompatibleClientIdentity() {
        assertEquals("clash.meta", SUBSCRIPTION_USER_AGENT)
    }
}
