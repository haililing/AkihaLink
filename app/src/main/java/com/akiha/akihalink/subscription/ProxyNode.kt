package com.akiha.akihalink.subscription

import kotlinx.serialization.json.JsonObject

data class ProxyNode(
    val id: String,
    val subscriptionId: String,
    val fingerprint: String,
    val name: String,
    val protocol: String,
    val endpoint: String,
    val outbound: JsonObject,
)

data class ParseResult(
    val nodes: List<ProxyNode>,
    val skipped: Int,
    val skipReasons: Map<String, Int> = emptyMap(),
) {
    fun warningMessage(): String? {
        if (skipReasons.isEmpty()) return null
        val details = skipReasons.entries
            .sortedByDescending { it.value }
            .joinToString("；") { (reason, count) -> "$reason（$count）" }
        return "已跳过 $skipped 个不可用节点：$details"
    }
}

class SubscriptionParseException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
