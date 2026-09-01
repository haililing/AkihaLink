package com.akiha.akihalink.clash

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class ClashApiClientTest {
    @Test
    fun selectsNodeWithBoundedAuthenticatedRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))

            ClashApiClient().selectNode("secret", "AkihaLink", "node tag", server.port)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/proxies/AkihaLink", request.path)
            assertEquals("Bearer secret", request.headers["Authorization"])
            assertEquals("{\"name\":\"node tag\"}", request.body.readUtf8())
        }
    }

    @Test
    fun selectorRequestTimesOutQuickly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(3, TimeUnit.SECONDS))

            val elapsed = measureTimeMillis {
                val failure = runCatching {
                    ClashApiClient().selectNode("secret", "AkihaLink", "node", server.port)
                }.exceptionOrNull()
                assertTrue(failure != null)
            }

            assertTrue("selector request took ${elapsed}ms", elapsed < 2_500)
        }
    }

    @Test
    fun waitsForAuthenticatedVersionEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"version\":\"test\"}"))

            ClashApiClient().awaitReady("secret", server.port, timeoutMillis = 3_000)

            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/version", request.path)
                assertEquals("Bearer secret", request.headers["Authorization"])
            }
        }
    }

    @Test
    fun requestsEncodedNodeDelayWithAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"delay\":86}"))

            val delay = ClashApiClient().delay(
                secret = "secret",
                port = server.port,
                nodeTag = "node tag",
                timeoutMillis = 3_000,
            )
            val request = server.takeRequest()

            assertEquals(86, delay)
            assertEquals("Bearer secret", request.headers["Authorization"])
            assertEquals("/proxies/node%20tag/delay", request.requestUrl?.encodedPath)
            assertEquals("https://www.gstatic.com/generate_204", request.requestUrl?.queryParameter("url"))
            assertEquals("3000", request.requestUrl?.queryParameter("timeout"))
        }
    }

    @Test
    fun mapsPerNodeFailuresWithoutHidingBatchErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(504))
            assertNull(ClashApiClient().delay("secret", server.port, "timeout"))

            server.enqueue(MockResponse().setResponseCode(401))
            val error = runCatching { ClashApiClient().delay("secret", server.port, "unauthorized") }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("鉴权"))
        }
    }

    @Test
    fun cancellationCancelsTheHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("{\"delay\":10}")
                    .setBodyDelay(30, TimeUnit.SECONDS),
            )
            val request = async { ClashApiClient().delay("secret", server.port, "slow") }
            server.takeRequest(2, TimeUnit.SECONDS)
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        }
    }
}
