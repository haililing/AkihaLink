package com.akiha.akihalink.subscription

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {
    private val parser = SubscriptionParser()

    @Test
    fun parsesBase64UriListAndSkipsBrokenLines() {
        val userInfo = base64("aes-128-gcm:secret")
        val list = "ss://$userInfo@example.com:443#Tokyo\ninvalid\nvless://00000000-0000-4000-8000-000000000004@example.org:8443?security=reality&sni=www.example.org&pbk=key&sid=01&type=grpc&serviceName=edge#Edge"
        val encoded = base64(list)

        val result = parser.parse(encoded.toByteArray(), "sub-1")

        assertEquals(2, result.nodes.size)
        assertEquals(setOf("shadowsocks", "vless"), result.nodes.map { it.protocol }.toSet())
        val vless = result.nodes.first { it.protocol == "vless" }.outbound.toString()
        assertTrue(vless.contains("\"reality\""))
        assertTrue(vless.contains("\"grpc\""))
    }

    @Test
    fun importsOnlyClashProxies() {
        val yaml = """
            port: 7890
            dns:
              nameserver: [8.8.8.8]
            proxies:
              - name: JP
                type: ss
                server: jp.example.com
                port: 443
                cipher: aes-128-gcm
                password: secret
              - name: Broken
                type: socks5
                server: bad.example.com
                port: 1080
        """.trimIndent()

        val result = parser.parse(yaml.toByteArray(), "sub-2")

        assertEquals(1, result.nodes.size)
        assertEquals("JP", result.nodes.single().name)
        assertFalse(result.nodes.single().outbound.containsKey("dns"))
    }

    @Test
    fun rejectsDuplicateYamlKeysAndAliasExpansion() {
        val duplicateKeys = """
            proxies: []
            proxies:
              - name: Hidden
                type: ss
                server: hidden.example.com
                port: 443
                cipher: aes-128-gcm
                password: secret
        """.trimIndent()
        assertThrows(SubscriptionParseException::class.java) {
            parser.parse(duplicateKeys.toByteArray(), "duplicate-keys")
        }

        val aliases = buildString {
            appendLine("template: &node")
            appendLine("  name: Alias")
            appendLine("  type: ss")
            appendLine("  server: alias.example.com")
            appendLine("  port: 443")
            appendLine("  cipher: aes-128-gcm")
            appendLine("  password: secret")
            appendLine("proxies:")
            repeat(33) { appendLine("  - *node") }
        }
        assertThrows(SubscriptionParseException::class.java) {
            parser.parse(aliases.toByteArray(), "alias-expansion")
        }
    }

    @Test
    fun parsesClashWhenUnicodePairCrossesYamlBufferBoundary() {
        val prefix = "proxies:\n  - name: "
        val name = "a".repeat(1_024 - prefix.length) + "\uD83D\uDE80"
        val yaml = "$prefix$name\n" +
            "    type: ss\n" +
            "    server: edge.example.com\n" +
            "    port: 443\n" +
            "    cipher: aes-128-gcm\n" +
            "    password: secret\n"

        val result = parser.parse(yaml.toByteArray(), "unicode-boundary")

        assertEquals(1, result.nodes.size)
    }

    @Test
    fun preservesClashMuxAndAnytlsSessionHints() {
        val yaml = """
            proxies:
              - name: SS Mux
                type: ss
                server: ss.example.com
                port: 443
                cipher: aes-128-gcm
                password: secret
                smux:
                  enabled: true
                  protocol: h2mux
                  max-connections: 3
              - name: AnyTLS Pool
                type: anytls
                server: any.example.com
                port: 443
                password: secret
                idle-session-check-interval: 30
                idle-session-timeout: 60
                min-idle-session: 2
        """.trimIndent()

        val nodes = parser.parse(yaml.toByteArray(), "clash-tuning").nodes
        val shadowsocks = nodes.first { it.protocol == "shadowsocks" }.outbound
        val anytls = nodes.first { it.protocol == "anytls" }.outbound

        assertEquals(
            "h2mux",
            shadowsocks.getValue("multiplex").jsonObject.getValue("protocol").jsonPrimitive.content,
        )
        assertEquals(
            3,
            shadowsocks.getValue("multiplex").jsonObject
                .getValue("max_connections").jsonPrimitive.content.toInt(),
        )
        assertEquals("30s", anytls.getValue("idle_session_check_interval").jsonPrimitive.content)
        assertEquals("60s", anytls.getValue("idle_session_timeout").jsonPrimitive.content)
        assertEquals(2, anytls.getValue("min_idle_session").jsonPrimitive.content.toInt())
    }

    @Test
    fun sanitizesSingBoxOutbounds() {
        val source = """
            {
              "inbounds": [{"type":"tun"}],
              "route": {"final":"evil"},
              "experimental": {"clash_api":{"external_controller":"0.0.0.0:9090"}},
              "outbounds": [
                {"type":"trojan","tag":"Home","server":"host.example","server_port":443,"password":"secret","detour":"evil","tcp_congestion_control":"cubic"},
                {"type":"socks","tag":"Ignored","server":"127.0.0.1","server_port":1080}
              ]
            }
        """.trimIndent()

        val node = parser.parse(source.toByteArray(), "sub-3").nodes.single()

        assertEquals("trojan", node.protocol)
        assertFalse(node.outbound.containsKey("detour"))
        assertFalse(node.outbound.containsKey("tag"))
        assertFalse(node.outbound.containsKey("tcp_congestion_control"))
    }

    @Test
    fun skipsNodesMissingProtocolRequirements() {
        val source = """
            {
              "outbounds": [
                {"type":"trojan","tag":"Valid","server":"good.example","server_port":443,"password":"secret"},
                {"type":"trojan","tag":"No password","server":"bad.example","server_port":443},
                {"type":"vless","tag":"Bad UUID","server":"bad.example","server_port":443,"uuid":"not-a-uuid"},
                {"type":"vmess","tag":"Bad transport","server":"bad.example","server_port":443,"uuid":"00000000-0000-4000-8000-000000000005","transport":{"type":"invalid"}}
              ]
            }
        """.trimIndent()

        val result = parser.parse(source.toByteArray(), "validated")

        assertEquals(listOf("Valid"), result.nodes.map { it.name })
        assertEquals(3, result.skipped)
    }

    @Test
    fun skipsTlsProtocolsWithoutExplicitTlsEnablement() {
        val source = """
            {
              "outbounds": [
                {"type":"hysteria2","tag":"HY2 valid","server":"hy.example","server_port":443,"password":"secret","tls":{"enabled":true,"server_name":"hy.example"}},
                {"type":"tuic","tag":"TUIC valid","server":"tuic.example","server_port":443,"uuid":"00000000-0000-4000-8000-000000000006","password":"secret","tls":{"enabled":true,"server_name":"tuic.example"}},
                {"type":"anytls","tag":"AnyTLS valid","server":"any.example","server_port":443,"password":"secret","tls":{"enabled":true,"server_name":"any.example"}},
                {"type":"hysteria2","tag":"HY2 missing TLS","server":"bad-hy.example","server_port":443,"password":"secret"},
                {"type":"tuic","tag":"TUIC disabled TLS","server":"bad-tuic.example","server_port":443,"uuid":"00000000-0000-4000-8000-000000000007","password":"secret","tls":{"enabled":false}},
                {"type":"anytls","tag":"AnyTLS missing TLS","server":"bad-any.example","server_port":443,"password":"secret"}
              ]
            }
        """.trimIndent()

        val result = parser.parse(source.toByteArray(), "tls-validation")

        assertEquals(setOf("hysteria2", "tuic", "anytls"), result.nodes.map { it.protocol }.toSet())
        assertEquals(3, result.skipped)
        assertEquals(2, result.skipReasons["Missing TLS options"])
        assertEquals(1, result.skipReasons["TLS must be enabled"])
        assertTrue(result.warningMessage().orEmpty().contains("TLS"))
    }

    @Test
    fun rejectsSubscriptionWhenEveryNodeIsIncomplete() {
        val source = """{"outbounds":[{"type":"trojan","server":"bad.example","server_port":443}]}"""
        assertThrows(SubscriptionParseException::class.java) {
            parser.parse(source.toByteArray(), "invalid")
        }
    }

    @Test
    fun rejectsOversizedAndZeroValidSubscriptions() {
        val oversized = ByteArray(SubscriptionParser.MAX_RESPONSE_BYTES + 1)
        assertThrows(SubscriptionParseException::class.java) { parser.parse(oversized, "sub") }
        assertThrows(SubscriptionParseException::class.java) {
            parser.parse("socks://127.0.0.1:1080".toByteArray(), "sub")
        }
    }

    @Test
    fun parsesVmessAndQuicProtocols() {
        val vmessJson = """{"v":"2","ps":"VMess","add":"vm.example.com","port":"443","id":"00000000-0000-4000-8000-000000000001","aid":"0","scy":"auto","net":"ws","path":"/edge","host":"cdn.example.com","tls":"tls","sni":"cdn.example.com"}"""
        val lines = listOf(
            "vmess://${base64(vmessJson)}",
            "hysteria2://password@hy.example.com:443?sni=hy.example.com&insecure=1#HY2",
            "tuic://00000000-0000-4000-8000-000000000002:password@tuic.example.com:443?sni=tuic.example.com#TUIC",
            "anytls://password@any.example.com:443?sni=any.example.com#AnyTLS",
        ).joinToString("\n")

        val nodes = parser.parse(lines.toByteArray(), "protocols").nodes

        assertEquals(setOf("vmess", "hysteria2", "tuic", "anytls"), nodes.map { it.protocol }.toSet())
        assertTrue(nodes.filter { it.protocol != "vmess" }.all { it.outbound.containsKey("tls") })
    }

    @Test
    fun deduplicatesIdenticalOutbounds() {
        val line = "trojan://password@example.com:443#One"
        val result = parser.parse("$line\n${line.replace("One", "Two")}".toByteArray(), "sub")
        assertEquals(1, result.nodes.size)
    }

    @Test
    fun deduplicatesOutboundsWithDifferentJsonKeyOrder() {
        val source = """
            {
              "outbounds": [
                {"type":"trojan","tag":"First","server":"example.com","server_port":443,"password":"secret","tls":{"enabled":true,"server_name":"example.com"}},
                {"password":"secret","server_port":443,"tag":"Second","tls":{"server_name":"example.com","enabled":true},"server":"example.com","type":"trojan"}
              ]
            }
        """.trimIndent()

        val result = parser.parse(source.toByteArray(), "canonical")

        assertEquals(1, result.nodes.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun capsNodeCount() {
        val payload = List(2_005) { "trojan://password@node-$it.example.com:443#Node-$it" }
            .joinToString("\n")
        val result = parser.parse(payload.toByteArray(), "sub")
        assertEquals(2_000, result.nodes.size)
    }

    @Test
    fun parsesOptionalExternalFixture() {
        val fixture = System.getProperty("akihalink.subscriptionFixture") ?: return
        val result = parser.parse(File(fixture).readBytes(), "external")
        println("External subscription parsed: nodes=${result.nodes.size}, skipped=${result.skipped}")
        assertTrue(result.nodes.isNotEmpty())
    }

    private fun base64(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
