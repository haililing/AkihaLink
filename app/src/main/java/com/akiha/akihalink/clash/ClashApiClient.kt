package com.akiha.akihalink.clash

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
private data class SelectorRequest(val name: String)

@Serializable
private data class DelayResponse(val delay: Int)

class ClashApiClient(private val client: OkHttpClient = newClient()) {
    suspend fun awaitReady(
        secret: String,
        port: Int,
        timeoutMillis: Int = READY_TIMEOUT_MILLIS,
    ) {
        require(port in 1..65_535) { "Invalid Clash API port" }
        require(timeoutMillis in 1..60_000) { "Invalid readiness timeout" }
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/version")
            .header("Authorization", "Bearer $secret")
            .get()
            .build()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        var lastError: Throwable? = null
        do {
            val responseCode = runCatching {
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response -> response.code }
                }
            }
            when (responseCode.getOrNull()) {
                200 -> return
                401 -> error("测速服务鉴权失败")
            }
            lastError = responseCode.exceptionOrNull()
            coroutineDelay(READY_RETRY_MILLIS)
        } while (System.nanoTime() < deadline)
        throw IllegalStateException("测速服务未就绪，请稍后重试", lastError)
    }

    suspend fun selectNode(
        secret: String,
        selector: String,
        nodeTag: String,
        port: Int = MAIN_CONTROLLER_PORT,
    ) = withContext(Dispatchers.IO) {
        require(port in 1..65_535) { "Invalid Clash API port" }
        val encoded = URLEncoder.encode(selector, StandardCharsets.UTF_8).replace("+", "%20")
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/proxies/$encoded")
            .header("Authorization", "Bearer $secret")
            .put(JSON.encodeToString(SelectorRequest(nodeTag)).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).also { call ->
            call.timeout().timeout(SELECTOR_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.execute().use { response ->
            if (!response.isSuccessful) error("节点切换失败 (${response.code})")
        }
    }

    suspend fun delay(
        secret: String,
        port: Int,
        nodeTag: String,
        url: String = TEST_URL,
        timeoutMillis: Int = TEST_TIMEOUT_MILLIS,
    ): Int? {
        require(port in 1..65_535) { "Invalid Clash API port" }
        require(timeoutMillis in 1..60_000) { "Invalid delay timeout" }
        val encoded = URLEncoder.encode(nodeTag, StandardCharsets.UTF_8).replace("+", "%20")
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/proxies/$encoded/delay?url=" +
                URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20") +
                "&timeout=$timeoutMillis")
            .header("Authorization", "Bearer $secret")
            .get()
            .build()
        return client.newCall(request).awaitDelay()
    }

    private suspend fun Call.awaitDelay(): Int? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    when (response.code) {
                        200 -> runCatching {
                            JSON.decodeFromString<DelayResponse>(response.body.string()).delay
                        }.onSuccess(continuation::resume)
                            .onFailure(continuation::resumeWithException)
                        503, 504 -> continuation.resume(null)
                        401 -> continuation.resumeWithException(IllegalStateException("测速鉴权失败"))
                        404 -> continuation.resumeWithException(IllegalStateException("节点配置已变化，请重试测速"))
                        else -> continuation.resumeWithException(
                            IllegalStateException("测速接口返回 ${response.code}"),
                        )
                    }
                }
            }
        })
    }

    private companion object {
        val JSON = Json
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MAIN_CONTROLLER_PORT = 9090
        const val TEST_URL = "https://www.gstatic.com/generate_204"
        const val TEST_TIMEOUT_MILLIS = 5_000
        const val SELECTOR_TIMEOUT_MILLIS = 1_500L
        const val READY_TIMEOUT_MILLIS = 8_000
        const val READY_RETRY_MILLIS = 150L
        fun newClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 20
                maxRequestsPerHost = 20
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(7, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .build()
        }
    }
}
