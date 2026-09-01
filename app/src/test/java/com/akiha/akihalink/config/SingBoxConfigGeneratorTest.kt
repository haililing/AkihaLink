package com.akiha.akihalink.config

import com.akiha.akihalink.subscription.ProxyNode
import com.akiha.akihalink.subscription.SubscriptionParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigGeneratorTest {
    private val node = ProxyNode(
        id = "node-id",
        subscriptionId = "sub",
        fingerprint = "fingerprint",
        name = "Node",
        protocol = "trojan",
        endpoint = "example.com:443",
        outbound = buildJsonObject {
            put("type", "trojan")
            put("server", "example.com")
            put("server_port", 443)
            put("password", "secret")
        },
    )

    @Test
    fun hotspotProxyIsOptInAndUsesAndroidWifiDiscovery() {
        fun inbound(mode: ProxyMode, enabled: Boolean) = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = mode,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "h".repeat(64),
                    hotspotProxyEnabled = enabled,
                ),
            ),
        ).jsonObject.getValue("inbounds").jsonArray.single().jsonObject

        ProxyMode.entries.filter { it != ProxyMode.DIRECT }.forEach { mode ->
            val localOnly = inbound(mode, false)
            assertEquals("local", localOnly.getValue("mode").jsonPrimitive.content)
            assertFalse(localOnly.containsKey("shared"))
            val hybrid = inbound(mode, true)
            assertEquals("hybrid", hybrid.getValue("mode").jsonPrimitive.content)
            val shared = hybrid.getValue("shared").jsonObject
            assertEquals("hijack", shared.getValue("dns_mode").jsonPrimitive.content)
            assertEquals("wifi", shared.getValue("android_tethering").jsonPrimitive.content)
            val advanced = shared.getValue("advanced").jsonObject
            assertEquals("1", advanced.getValue("tc_priority").jsonPrimitive.content)
            assertEquals("rewrite", advanced.getValue("data_plane").jsonPrimitive.content)
            assertFalse(shared.containsKey("interface"))
            assertFalse(hybrid.containsKey("shared_network"))
        }
    }

    @Test
    fun usesSingleEditionRuntimePolicy() {
        val root = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.RULE,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "p".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions,
                ),
            ),
        ).jsonObject
        val logLevel = root.getValue("log").jsonObject.getValue("level").jsonPrimitive.content
        val dns = root.getValue("dns").jsonObject
        val outbound = root.getValue("outbounds").jsonArray.first().jsonObject

        assertEquals("warn", logLevel)
        assertTrue(dns.containsKey("reverse_mapping"))
        assertEquals("30m", dns.getValue("optimistic").jsonObject.getValue("timeout").jsonPrimitive.content)
        assertFalse(outbound.containsKey("tcp_congestion_control"))
    }

    @Test
    fun generatesRuleModeEbpfConfig() {
        val root = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.RULE,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = setOf(10_119),
                    appUid = 10_456,
                    androidUserIds = setOf(0, 10),
                    clashSecret = "a".repeat(64),
                ),
            ),
        ).jsonObject

        val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
        val local = inbound.getValue("local").jsonObject
        assertEquals("ebpf", inbound.getValue("type").jsonPrimitive.content)
        assertEquals("local", inbound.getValue("mode").jsonPrimitive.content)
        assertEquals("hijack", local.getValue("dns_mode").jsonPrimitive.content)
        assertFalse(inbound.containsKey("include_android_system_resolver"))
        assertFalse(inbound.containsKey("redirect_address"))
        assertFalse(inbound.containsKey("cgroup_enabled"))
        assertFalse(inbound.containsKey("shared_network"))
        assertEquals(listOf("tcp", "udp"), inbound.getValue("network").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            emptyList<String>(),
            local.getValue("include_uid").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            emptyList<String>(),
            local.getValue("include_uid_range").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("10119", "10456", "20119", "20456"),
            local.getValue("exclude_uid").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(inbound.containsKey("bypass_rule_set"))
        assertEquals("local-dns", root.getValue("route").jsonObject.getValue("default_domain_resolver").jsonPrimitive.content)
        val dnsServers = root.getValue("dns").jsonObject.getValue("servers").jsonArray
        val bootstrapDns = dnsServers.first().jsonObject
        assertEquals("local-dns", bootstrapDns.getValue("tag").jsonPrimitive.content)
        assertEquals("https", bootstrapDns.getValue("type").jsonPrimitive.content)
        assertEquals("223.5.5.5", bootstrapDns.getValue("server").jsonPrimitive.content)
        assertEquals(
            "dns.alidns.com",
            bootstrapDns.getValue("tls").jsonObject.getValue("server_name").jsonPrimitive.content,
        )
        assertFalse(bootstrapDns.containsKey("detour"))
        assertFalse(dnsServers.any { it.jsonObject["type"]?.jsonPrimitive?.content == "local" })

        val dnsRules = root.getValue("dns").jsonObject.getValue("rules").jsonArray
        assertEquals(5, dnsRules.size)
        val chinaDnsRule = dnsRules[0].jsonObject
        assertEquals(
            listOf("geosite-geolocation-cn"),
            chinaDnsRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("local-dns", chinaDnsRule.getValue("server").jsonPrimitive.content)
        assertEquals("route", chinaDnsRule.getValue("action").jsonPrimitive.content)
        val nonChinaDnsRule = dnsRules[1].jsonObject
        assertEquals(
            listOf("geosite-geolocation-!cn"),
            nonChinaDnsRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("proxy-dns", nonChinaDnsRule.getValue("server").jsonPrimitive.content)
        assertEquals("route", nonChinaDnsRule.getValue("action").jsonPrimitive.content)
        val evaluateLocalDnsRule = dnsRules[2].jsonObject
        assertEquals("evaluate", evaluateLocalDnsRule.getValue("action").jsonPrimitive.content)
        assertEquals("local-dns", evaluateLocalDnsRule.getValue("server").jsonPrimitive.content)
        assertEquals("local-cn-candidate", evaluateLocalDnsRule.getValue("tag").jsonPrimitive.content)
        assertEquals("1.2s", evaluateLocalDnsRule.getValue("timeout").jsonPrimitive.content)
        val chinaResponseRule = dnsRules[3].jsonObject
        assertEquals("local-cn-candidate", chinaResponseRule.getValue("match_response").jsonPrimitive.content)
        assertEquals(
            listOf("geoip-cn"),
            chinaResponseRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("respond", chinaResponseRule.getValue("action").jsonPrimitive.content)
        assertEquals("true", chinaResponseRule.getValue("race").jsonPrimitive.content)
        val speculativeProxyDnsRule = dnsRules[4].jsonObject
        assertEquals("route", speculativeProxyDnsRule.getValue("action").jsonPrimitive.content)
        assertEquals("proxy-dns", speculativeProxyDnsRule.getValue("server").jsonPrimitive.content)
        assertEquals("true", speculativeProxyDnsRule.getValue("speculative").jsonPrimitive.content)
        assertFalse(dnsRules.any { it.jsonObject.containsKey("domain_suffix") })
        assertFalse(root.toString().contains("google.com"))

        val routeRules = root.getValue("route").jsonObject.getValue("rules").jsonArray
        val sniffIndex = routeRules.indexOfFirst { it.jsonObject["action"]?.jsonPrimitive?.content == "sniff" }
        val chinaDomainIndex = routeRules.indexOfFirst {
            it.jsonObject["action"]?.jsonPrimitive?.content == "direct" &&
                it.jsonObject["rule_set"]?.jsonArray?.any {
                    set -> set.jsonPrimitive.content == "geosite-geolocation-cn"
                } == true
        }
        val nonChinaDomainIndex = routeRules.indexOfFirst {
            it.jsonObject["action"]?.jsonPrimitive?.content == "route" &&
                it.jsonObject["rule_set"]?.jsonArray?.any {
                    set -> set.jsonPrimitive.content == "geosite-geolocation-!cn"
                } == true
        }
        val chinaIpIndex = routeRules.indexOfFirst {
            it.jsonObject["action"]?.jsonPrimitive?.content == "route" &&
                it.jsonObject["rule_set"]?.jsonArray?.any { set -> set.jsonPrimitive.content == "geoip-cn" } == true
        }
        assertTrue(sniffIndex in 0 until chinaDomainIndex)
        assertTrue(chinaDomainIndex in 0 until nonChinaDomainIndex)
        assertTrue(nonChinaDomainIndex in 0 until chinaIpIndex)

        val sniffRule = routeRules[sniffIndex].jsonObject
        assertEquals(
            listOf("tls", "http", "quic"),
            sniffRule.getValue("sniffer").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("200ms", sniffRule.getValue("timeout").jsonPrimitive.content)

        val chinaDomainRule = routeRules[chinaDomainIndex].jsonObject
        assertEquals(
            listOf("geosite-geolocation-cn"),
            chinaDomainRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("direct", chinaDomainRule.getValue("action").jsonPrimitive.content)

        val nonChinaDomainRule = routeRules[nonChinaDomainIndex].jsonObject
        assertEquals(
            listOf("geosite-geolocation-!cn"),
            nonChinaDomainRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("route", nonChinaDomainRule.getValue("action").jsonPrimitive.content)
        assertEquals("AkihaLink", nonChinaDomainRule.getValue("outbound").jsonPrimitive.content)

        val chinaIpRule = routeRules[chinaIpIndex].jsonObject
        assertEquals(
            listOf("geoip-cn"),
            chinaIpRule.getValue("rule_set").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("route", chinaIpRule.getValue("action").jsonPrimitive.content)
        assertEquals("akihalink-cn-fallback", chinaIpRule.getValue("outbound").jsonPrimitive.content)
        assertFalse(routeRules.any { it.jsonObject.containsKey("domain_regex") })
        assertFalse(routeRules.any { it.jsonObject.containsKey("domain_suffix") })
        assertEquals("AkihaLink", root.getValue("route").jsonObject.getValue("final").jsonPrimitive.content)
        val bootstrapRoute = routeRules.first { it.jsonObject.containsKey("ip_cidr") }.jsonObject
        assertEquals(
            listOf("223.5.5.5/32"),
            bootstrapRoute.getValue("ip_cidr").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("direct", bootstrapRoute.getValue("action").jsonPrimitive.content)
        val dnsHijackRoute = routeRules.first { it.jsonObject["port"]?.jsonPrimitive?.content == "53" }.jsonObject
        assertEquals("hijack-dns", dnsHijackRoute.getValue("action").jsonPrimitive.content)
        assertTrue(root.toString().contains("hijack-dns"))
        assertTrue(root.toString().contains("geoip-cn.srs"))
        assertTrue(root.toString().contains("geosite-geolocation-cn.srs"))
        assertTrue(root.toString().contains("geosite-geolocation-!cn.srs"))
        val outbounds = root.getValue("outbounds").jsonArray.associateBy {
            it.jsonObject.getValue("tag").jsonPrimitive.content
        }
        val directOutbound = requireNotNull(outbounds[SingBoxConfigGenerator.DIRECT_OUTBOUND_TAG]).jsonObject
        assertEquals("direct", directOutbound.getValue("type").jsonPrimitive.content)
        val fallbackOutbound = requireNotNull(outbounds[SingBoxConfigGenerator.FALLBACK_OUTBOUND_TAG]).jsonObject
        assertEquals(
            SingBoxConfigGenerator.FALLBACK_OUTBOUND_TYPE,
            fallbackOutbound.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            SingBoxConfigGenerator.DIRECT_OUTBOUND_TAG,
            fallbackOutbound.getValue("primary").jsonPrimitive.content,
        )
        assertEquals(
            SingBoxConfigGenerator.SELECTOR_TAG,
            fallbackOutbound.getValue("fallback").jsonPrimitive.content,
        )
        assertEquals("1.5s", fallbackOutbound.getValue("connect_timeout").jsonPrimitive.content)
        assertFalse(root.toString().contains("\"tun\""))
    }

    @Test
    fun globalModeDoesNotLoadCnRules() {
        val config = SingBoxConfigGenerator().generate(
            ConfigRequest(
                mode = ProxyMode.GLOBAL,
                nodes = listOf(node),
                selectedNodeId = node.id,
                excludedUids = emptySet(),
                appUid = 10_456,
                clashSecret = "b".repeat(64),
            ),
        )
        assertFalse(config.contains("geoip-cn"))
        assertFalse(config.contains("geosite-cn"))
        assertFalse(config.contains(SingBoxConfigGenerator.FALLBACK_OUTBOUND_TYPE))
        assertFalse(config.contains(SingBoxConfigGenerator.DIRECT_OUTBOUND_TAG))
        assertFalse(config.contains("\"sniff\""))
        assertTrue(config.contains("127.0.0.1:9090"))
        assertFalse(config.contains("tcp_congestion_control"))
        val inbound = Json.parseToJsonElement(config).jsonObject
            .getValue("inbounds").jsonArray.single().jsonObject
        val local = inbound.getValue("local").jsonObject
        assertEquals("local", inbound.getValue("mode").jsonPrimitive.content)
        assertEquals("hijack", local.getValue("dns_mode").jsonPrimitive.content)
        assertFalse(inbound.containsKey("include_android_system_resolver"))
        assertEquals(
            emptyList<String>(),
            local.getValue("include_uid").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            emptyList<String>(),
            local.getValue("include_uid_range").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun automaticDnsPolicyCoversEveryProxyMode() {
        listOf(ProxyMode.GLOBAL, ProxyMode.RULE).forEach { mode ->
            val root = Json.parseToJsonElement(
                SingBoxConfigGenerator().generate(
                    ConfigRequest(
                        mode = mode,
                        nodes = listOf(node),
                        selectedNodeId = node.id,
                        excludedUids = emptySet(),
                        appUid = 10_456,
                        clashSecret = "dns".repeat(16),
                        runtimeOptions = AppConfigPolicy.runtimeOptions,
                    ),
                ),
            ).jsonObject

            val dns = root.getValue("dns").jsonObject
            val servers = dns.getValue("servers").jsonArray.associateBy {
                it.jsonObject.getValue("tag").jsonPrimitive.content
            }
            assertEquals(setOf("local-dns", "proxy-dns"), servers.keys)
            val localDns = requireNotNull(servers["local-dns"]).jsonObject
            assertEquals("https", localDns.getValue("type").jsonPrimitive.content)
            assertEquals("223.5.5.5", localDns.getValue("server").jsonPrimitive.content)
            assertEquals("443", localDns.getValue("server_port").jsonPrimitive.content)
            assertEquals("/dns-query", localDns.getValue("path").jsonPrimitive.content)
            assertEquals(
                "dns.alidns.com",
                localDns.getValue("tls").jsonObject.getValue("server_name").jsonPrimitive.content,
            )
            val proxyDns = requireNotNull(servers["proxy-dns"]).jsonObject
            assertEquals("https", proxyDns.getValue("type").jsonPrimitive.content)
            assertEquals("1.1.1.1", proxyDns.getValue("server").jsonPrimitive.content)
            assertEquals("443", proxyDns.getValue("server_port").jsonPrimitive.content)
            assertEquals("/dns-query", proxyDns.getValue("path").jsonPrimitive.content)
            assertEquals(SingBoxConfigGenerator.SELECTOR_TAG, proxyDns.getValue("detour").jsonPrimitive.content)
            assertEquals("proxy-dns", dns.getValue("final").jsonPrimitive.content)
            assertEquals(mode == ProxyMode.RULE, dns.containsKey("reverse_mapping"))

            val inbound = root.getValue("inbounds").jsonArray.single().jsonObject
            val local = inbound.getValue("local").jsonObject
            assertEquals("local", inbound.getValue("mode").jsonPrimitive.content)
            assertEquals("hijack", local.getValue("dns_mode").jsonPrimitive.content)
            assertFalse(inbound.containsKey("redirect_address"))
            assertEquals(
                emptyList<String>(),
                local.getValue("include_uid").jsonArray.map { it.jsonPrimitive.content },
            )
            val route = root.getValue("route").jsonObject
            assertEquals("local-dns", route.getValue("default_domain_resolver").jsonPrimitive.content)
            val dnsHijack = route.getValue("rules").jsonArray.single { rule ->
                rule.jsonObject["port"]?.jsonPrimitive?.content == "53"
            }.jsonObject
            assertEquals("hijack-dns", dnsHijack.getValue("action").jsonPrimitive.content)
        }
    }

    @Test
    fun preservesSubscriptionTransportSettingsWithoutAddingTcpTuning() {
        val subscriptionNode = protocolNode("anytls", "anytls") {
            put("password", "secret")
            put("multiplex", buildJsonObject {
                put("enabled", true)
                put("protocol", "h2mux")
            })
            put("min_idle_session", 2)
            put("idle_session_timeout", "60s")
        }
        val root = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL,
                    nodes = listOf(subscriptionNode),
                    selectedNodeId = subscriptionNode.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "f".repeat(64),
                ),
            ),
        ).jsonObject
        val generated = root.getValue("outbounds").jsonArray.first().jsonObject

        assertEquals("h2mux", generated.getValue("multiplex").jsonObject.getValue("protocol").jsonPrimitive.content)
        assertEquals(2, generated.getValue("min_idle_session").jsonPrimitive.content.toInt())
        assertEquals("60s", generated.getValue("idle_session_timeout").jsonPrimitive.content)
        assertFalse(generated.containsKey("tcp_congestion_control"))
        assertFalse(generated.containsKey("tcp_keep_alive"))
        assertFalse(generated.containsKey("tcp_fast_open"))
    }

    @Test
    fun parserDropsUntrustedTcpTuningFields() {
        val untrustedNode = node.copy(
            outbound = buildJsonObject {
                node.outbound.forEach { (key, value) -> put(key, value) }
                put("tcp_congestion_control", "cubic")
                put("tcp_keep_alive", "10s")
                put("tcp_fast_open", true)
            },
        )
        val parsed = SubscriptionParser().parse(
            """{"outbounds":[${untrustedNode.outbound}]}""".toByteArray(),
            "untrusted",
        )
        val outbound = parsed.nodes.single().outbound

        assertFalse(outbound.containsKey("tcp_congestion_control"))
        assertFalse(outbound.containsKey("tcp_keep_alive"))
        assertFalse(outbound.containsKey("tcp_fast_open"))
    }

    @Test
    fun directModeCannotGenerateListener() {
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.DIRECT,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "c".repeat(64),
                ),
            )
        }
    }

    @Test
    fun generatesIsolatedSpeedTestConfigWithoutInboundOrSelector() {
        val root = Json.parseToJsonElement(
            SpeedTestConfigGenerator().generate(
                SpeedTestConfigRequest(
                    nodes = listOf(node),
                    clashSecret = "s".repeat(64),
                ),
            ),
        ).jsonObject

        assertFalse(root.containsKey("inbounds"))
        assertFalse(root.toString().contains("\"selector\""))
        assertFalse(root.toString().contains("ebpf"))
        assertFalse(root.toString().contains("tun"))
        assertEquals(1, root.getValue("outbounds").jsonArray.size)
        assertFalse(root.getValue("outbounds").jsonArray.single().jsonObject.containsKey("tcp_congestion_control"))
        assertEquals(
            "127.0.0.1:19090",
            root.getValue("experimental").jsonObject.getValue("clash_api").jsonObject
                .getValue("external_controller").jsonPrimitive.content,
        )
        assertFalse(root.getValue("experimental").jsonObject.containsKey("cache_file"))
        assertEquals(
            "local-dns",
            root.getValue("route").jsonObject.getValue("default_domain_resolver").jsonPrimitive.content,
        )
    }

    @Test
    fun keepsSingleUserSelectorForRoutingAndProxyDns() {
        val root = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL,
                    nodes = listOf(node, node.copy(id = "second")),
                    selectedNodeId = node.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "q".repeat(64),
                ),
            ),
        ).jsonObject
        val outbounds = root.getValue("outbounds").jsonArray.map { it.jsonObject }
        val selectors = outbounds.filter { it["type"]?.jsonPrimitive?.content == "selector" }

        assertEquals(1, selectors.size)
        assertEquals(SingBoxConfigGenerator.SELECTOR_TAG, selectors.single().getValue("tag").jsonPrimitive.content)
        assertFalse(root.toString().contains("\"urltest\""))
        assertEquals(
            SingBoxConfigGenerator.SELECTOR_TAG,
            root.getValue("route").jsonObject.getValue("final").jsonPrimitive.content,
        )
        assertTrue(
            root.getValue("dns").toString()
                .contains("\"detour\":\"${SingBoxConfigGenerator.SELECTOR_TAG}\""),
        )
    }

    @Test
    fun runtimePolicyOnlyControlsDnsBehavior() {
        val ss = protocolNode("ss", "shadowsocks") {
            put("method", "aes-128-gcm")
            put("password", "secret")
        }
        val options = RuntimeConfigOptions(
            logLevel = "warn",
            reverseDnsMapping = true,
            optimisticDnsCache = true,
        )
        val defaultRoot = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.RULE,
                    nodes = listOf(ss),
                    selectedNodeId = ss.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "x".repeat(64),
                    runtimeOptions = options,
                ),
            ),
        ).jsonObject
        val dns = defaultRoot.getValue("dns").jsonObject
        assertEquals("warn", defaultRoot.getValue("log").jsonObject.getValue("level").jsonPrimitive.content)
        assertTrue(dns.getValue("reverse_mapping").jsonPrimitive.content.toBoolean())
        assertEquals("30m", dns.getValue("optimistic").jsonObject.getValue("timeout").jsonPrimitive.content)
        assertFalse(defaultRoot.toString().contains("akihalink_fingerprint"))

        val globalDns = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL,
                    nodes = listOf(ss),
                    selectedNodeId = ss.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "z".repeat(64),
                    runtimeOptions = options,
                ),
            ),
        ).jsonObject.getValue("dns").jsonObject
        assertFalse(globalDns.containsKey("reverse_mapping"))
        assertTrue(globalDns.containsKey("optimistic"))
    }

    @Test
    fun ruleModeCanDisableReverseDnsMapping() {
        val root = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.RULE,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "r".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions.copy(reverseDnsMapping = false),
                ),
            ),
        ).jsonObject

        assertFalse(root.getValue("dns").jsonObject.containsKey("reverse_mapping"))
    }

    @Test
    fun selectorUsesUpstreamUdp443BehaviorAndKeepsQuicNodes() {
        val hysteria2 = protocolNode("hy2", "hysteria2") { put("password", "secret") }
        val tuic = protocolNode("tuic", "tuic") {
            put("uuid", "00000000-0000-4000-8000-000000000003")
            put("password", "secret")
            put("udp_relay_mode", "quic")
        }
        val nodes = listOf(hysteria2, tuic)
        val root = Json.parseToJsonElement(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL, nodes = nodes, selectedNodeId = hysteria2.id,
                    excludedUids = emptySet(), appUid = 10_456, clashSecret = "h".repeat(64),
                ),
            ),
        ).jsonObject
        val outbounds = root.getValue("outbounds").jsonArray.map { it.jsonObject }
        val selector = outbounds.last()
        val speedTestRoot = Json.parseToJsonElement(
            SpeedTestConfigGenerator().generate(
                SpeedTestConfigRequest(nodes = nodes, clashSecret = "h".repeat(64)),
            ),
        ).jsonObject

        assertFalse(selector.containsKey("udp_443_fallback_outbounds"))
        assertEquals(listOf("hysteria2", "tuic"), outbounds.dropLast(1).map { it.getValue("type").jsonPrimitive.content })
        assertFalse(speedTestRoot.toString().contains("udp_443_fallback_outbounds"))
        assertEquals(
            listOf("hysteria2", "tuic"),
            speedTestRoot.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
    }

    @Test
    fun emitsFixturesForPinnedSingBoxSchemaCheck() {
        val fixtureDirectory = System.getProperty("akihalink.fixtureDir") ?: return
        val directory = File(fixtureDirectory).apply { mkdirs() }
        listOf(ProxyMode.GLOBAL, ProxyMode.RULE).forEach { mode ->
            val config = SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = mode,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = setOf(10_123),
                    appUid = 10_456,
                    androidUserIds = setOf(0, 10),
                    clashSecret = "d".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions,
                ),
            )
            File(directory, "${mode.wireName}.json").writeText(config)
        }
        File(directory, "hybrid.json").writeText(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL,
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    excludedUids = setOf(10_123),
                    appUid = 10_456,
                    androidUserIds = setOf(0, 10),
                    clashSecret = "w".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions,
                    hotspotProxyEnabled = true,
                ),
            ),
        )
        val protocolNodes = listOf(
            protocolNode("ss", "shadowsocks") {
                put("method", "aes-128-gcm"); put("password", "secret")
            },
            protocolNode("vmess", "vmess") {
                put("uuid", "00000000-0000-4000-8000-000000000001"); put("security", "auto")
            },
            protocolNode("vless", "vless") {
                put("uuid", "00000000-0000-4000-8000-000000000002")
            },
            protocolNode("trojan", "trojan") { put("password", "secret") },
            protocolNode("hy2", "hysteria2") {
                put("password", "secret"); put("tls", tls("hy.example.com"))
            },
            protocolNode("tuic", "tuic") {
                put("uuid", "00000000-0000-4000-8000-000000000003")
                put("password", "secret"); put("congestion_control", "bbr")
                put("tls", tls("tuic.example.com"))
            },
            protocolNode("anytls", "anytls") {
                put("password", "secret"); put("tls", tls("anytls.example.com"))
            },
        )
        File(directory, "all-protocols.json").writeText(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL,
                    nodes = protocolNodes,
                    selectedNodeId = protocolNodes.first().id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "e".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions,
                ),
            ),
        )
        val transportNodes = listOf("http", "ws", "quic", "grpc", "httpupgrade").mapIndexed { index, type ->
            protocolNode("vmess-$type", "vmess") {
                put("uuid", "00000000-0000-4000-8000-${(index + 10).toString().padStart(12, '0')}")
                put("security", "auto")
                putJsonObject("transport") {
                    put("type", type)
                    when (type) {
                        "http", "ws", "httpupgrade" -> put("path", "/benchmark-$type")
                        "grpc" -> put("service_name", "benchmark")
                    }
                }
                if (type == "quic") {
                    put("tls", tls("quic.example.com"))
                }
            }
        }
        File(directory, "all-transports.json").writeText(
            SingBoxConfigGenerator().generate(
                ConfigRequest(
                    mode = ProxyMode.GLOBAL,
                    nodes = transportNodes,
                    selectedNodeId = transportNodes.first().id,
                    excludedUids = emptySet(),
                    appUid = 10_456,
                    clashSecret = "k".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions,
                ),
            ),
        )
        File(directory, "speedtest.json").writeText(
            SpeedTestConfigGenerator().generate(
                SpeedTestConfigRequest(
                    nodes = protocolNodes,
                    clashSecret = "j".repeat(64),
                    runtimeOptions = AppConfigPolicy.runtimeOptions,
                ),
            ),
        )
    }

    @Test
    fun emitsConfigForOptionalExternalSubscription() {
        val fixture = System.getProperty("akihalink.subscriptionFixture") ?: return
        val fixtureDirectory = System.getProperty("akihalink.fixtureDir") ?: return
        val nodes = SubscriptionParser().parse(File(fixture).readBytes(), "external-config").nodes
        val config = SingBoxConfigGenerator().generate(
            ConfigRequest(
                mode = ProxyMode.GLOBAL,
                nodes = nodes,
                selectedNodeId = nodes.first().id,
                excludedUids = emptySet(),
                appUid = 10_456,
                clashSecret = "i".repeat(64),
            ),
        )
        File(fixtureDirectory, "external-subscription.json").writeText(config)
    }

    private fun protocolNode(
        id: String,
        type: String,
        configure: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): ProxyNode = ProxyNode(
        id = id,
        subscriptionId = "protocols",
        fingerprint = "a".repeat(64),
        name = type,
        protocol = type,
        endpoint = "$type.example.com:443",
        outbound = buildJsonObject {
            put("type", type)
            put("server", "$type.example.com")
            put("server_port", 443)
            configure()
        },
    )

    private fun tls(serverName: String) = buildJsonObject {
        put("enabled", true)
        put("server_name", serverName)
    }
}
