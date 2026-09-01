package com.akiha.akihalink.benchmark

import android.content.Context
import com.akiha.akihalink.data.AkihaLinkDatabase
import com.akiha.akihalink.data.AkihaLinkRepository
import com.akiha.akihalink.data.AppSettingEntity
import com.akiha.akihalink.data.ProxyNodeEntity
import com.akiha.akihalink.data.SubscriptionEntity
import com.akiha.akihalink.security.SecretCipher
import kotlinx.coroutines.runBlocking

object BenchmarkBootstrap {
    @JvmStatic
    fun seed(context: Context) = runBlocking {
        val dao = AkihaLinkDatabase.get(context).dao()
        if (dao.getNodes().size == NODE_COUNT) return@runBlocking
        val cipher = SecretCipher()
        val subscriptionId = "benchmark"
        val subscription = SubscriptionEntity(
            id = subscriptionId,
            name = "Benchmark fixture",
            encryptedUrl = cipher.encrypt("https://benchmark.invalid/subscription"),
            lastUpdatedAt = 1L,
        )
        val nodes = List(NODE_COUNT) { index ->
            val protocol = when (index % 3) {
                0 -> "shadowsocks"
                1 -> "vmess"
                else -> "anytls"
            }
            val id = "benchmark-$index"
            ProxyNodeEntity(
                id = id,
                subscriptionId = subscriptionId,
                fingerprint = index.toString(16).padStart(64, '0'),
                name = "Benchmark Node ${index.toString().padStart(3, '0')}",
                protocol = protocol,
                endpoint = "198.18.${index / 250}.${index % 250 + 1}:443",
                encryptedOutbound = cipher.encrypt(outbound(protocol, index)),
            )
        }
        dao.replaceSubscription(subscription, nodes)
        dao.putSetting(AppSettingEntity(AkihaLinkRepository.SETTING_SELECTED_NODE, nodes.first().id))
    }

    private fun outbound(protocol: String, index: Int): String = when (protocol) {
        "shadowsocks" ->
            """{"type":"shadowsocks","tag":"node-$index","server":"198.18.0.1","server_port":443,"method":"aes-128-gcm","password":"benchmark"}"""
        "vmess" ->
            """{"type":"vmess","tag":"node-$index","server":"198.18.0.2","server_port":443,"uuid":"00000000-0000-4000-8000-${index.toString().padStart(12, '0')}","security":"auto"}"""
        else ->
            """{"type":"anytls","tag":"node-$index","server":"198.18.0.3","server_port":443,"password":"benchmark","tls":{"enabled":true,"server_name":"benchmark.invalid"}}"""
    }

    private const val NODE_COUNT = 500
}
