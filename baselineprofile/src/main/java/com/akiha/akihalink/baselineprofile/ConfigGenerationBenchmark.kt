package com.akiha.akihalink.baselineprofile

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akiha.akihalink.config.ConfigRequest
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.config.RuntimeConfigOptions
import com.akiha.akihalink.config.SingBoxConfigGenerator
import com.akiha.akihalink.subscription.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigGenerationBenchmark {
    @get:Rule
    val benchmark = BenchmarkRule()

    @Test fun oneNodeGlobal() = measure(1, ProxyMode.GLOBAL)
    @Test fun oneNodeRule() = measure(1, ProxyMode.RULE)
    @Test fun hundredNodesGlobal() = measure(100, ProxyMode.GLOBAL)
    @Test fun hundredNodesRule() = measure(100, ProxyMode.RULE)
    @Test fun fiveHundredNodesGlobal() = measure(500, ProxyMode.GLOBAL)
    @Test fun fiveHundredNodesRule() = measure(500, ProxyMode.RULE)

    private fun measure(count: Int, mode: ProxyMode) {
        val nodes = List(count, ::node)
        val request = ConfigRequest(
            mode = mode,
            nodes = nodes,
            selectedNodeId = nodes.first().id,
            excludedUids = emptySet(),
            appUid = 10_000,
            clashSecret = "b".repeat(32),
            runtimeOptions = RuntimeConfigOptions(
                logLevel = "warn",
                reverseDnsMapping = true,
                optimisticDnsCache = true,
            ),
        )
        val generator = SingBoxConfigGenerator()
        benchmark.measureRepeated {
            generator.generate(request)
        }
    }

    private fun node(index: Int): ProxyNode {
        val protocol = when (index % 3) {
            0 -> "shadowsocks"
            1 -> "vmess"
            else -> "anytls"
        }
        val id = "benchmark-$index"
        val outbound = when (protocol) {
            "shadowsocks" ->
                """{"type":"shadowsocks","server":"198.18.0.1","server_port":443,"method":"aes-128-gcm","password":"benchmark"}"""
            "vmess" ->
                """{"type":"vmess","server":"198.18.0.2","server_port":443,"uuid":"00000000-0000-4000-8000-${index.toString().padStart(12, '0')}","security":"auto"}"""
            else ->
                """{"type":"anytls","server":"198.18.0.3","server_port":443,"password":"benchmark","tls":{"enabled":true,"server_name":"benchmark.invalid"}}"""
        }
        return ProxyNode(
            id = id,
            subscriptionId = "benchmark",
            fingerprint = index.toString(16).padStart(64, '0'),
            name = id,
            protocol = protocol,
            endpoint = "198.18.0.1:443",
            outbound = Json.parseToJsonElement(outbound).jsonObject,
        )
    }
}
