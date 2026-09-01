package com.akiha.akihalink.performance

import androidx.tracing.Trace
import androidx.tracing.trace
import java.security.SecureRandom

data class TraceContext(val id: String, val cookie: Int) {
    init {
        require(ID_PATTERN.matches(id)) { "Trace id must be 16 lowercase hexadecimal characters" }
        require(cookie >= 0) { "Trace cookie must be non-negative" }
    }

    companion object {
        val ID_PATTERN = Regex("[0-9a-f]{16}")
    }
}

private val random = SecureRandom()

internal fun anonymousTraceContext(): TraceContext {
    val bytes = ByteArray(8).also(random::nextBytes)
    val id = bytes.joinToString("") { "%02x".format(it) }
    val cookie = (
        (bytes[4].toInt() and 0xff shl 24) or
            (bytes[5].toInt() and 0xff shl 16) or
            (bytes[6].toInt() and 0xff shl 8) or
            (bytes[7].toInt() and 0xff)
        ) and Int.MAX_VALUE
    return TraceContext(id, cookie)
}

object PerformanceTrace {
    fun create(): TraceContext = anonymousTraceContext()

    fun beginAsync(name: String, context: TraceContext) {
        Trace.beginAsyncSection("$name/${context.id}", context.cookie)
    }

    fun endAsync(name: String, context: TraceContext) {
        Trace.endAsyncSection("$name/${context.id}", context.cookie)
    }

    fun <T> section(name: String, context: TraceContext? = null, block: () -> T): T {
        return trace(sectionName(name, context), block)
    }

    suspend fun <T> suspendSection(
        name: String,
        context: TraceContext? = null,
        block: suspend () -> T,
    ): T {
        val traceContext = context ?: create()
        beginAsync(name, traceContext)
        return try {
            block()
        } finally {
            endAsync(name, traceContext)
        }
    }

    private fun sectionName(name: String, context: TraceContext?): String =
        context?.let { "$name/${it.id}" } ?: name
}
