package com.akiha.akihalink.config

import com.akiha.akihalink.subscription.ProxyNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ConfigRequest(
    val mode: ProxyMode,
    val nodes: List<ProxyNode>,
    val selectedNodeId: String,
    val excludedUids: Set<Int>,
    val appUid: Int,
    val androidUserIds: Set<Int> = setOf(0),
    val clashSecret: String,
    val runtimeOptions: RuntimeConfigOptions = RuntimeConfigOptions(),
    val hotspotProxyEnabled: Boolean = false,
    val dataDirectory: String = "/data/adb/akihalink",
)

data class SpeedTestConfigRequest(
    val nodes: List<ProxyNode>,
    val clashSecret: String,
    val runtimeOptions: RuntimeConfigOptions = RuntimeConfigOptions(),
)

data class RuntimeConfigOptions(
    val logLevel: String = "info",
    val reverseDnsMapping: Boolean = false,
    val optimisticDnsCache: Boolean = false,
)

class SingBoxConfigGenerator {
    fun generate(request: ConfigRequest): String {
        require(request.mode != ProxyMode.DIRECT) { "Direct mode must stop sing-box instead of generating a config" }
        require(request.nodes.isNotEmpty()) { "At least one node is required" }
        val selectedIndex = request.nodes.indexOfFirst { it.id == request.selectedNodeId }
        require(selectedIndex >= 0) { "Selected node is unavailable" }
        require(request.clashSecret.length >= 32) { "Clash API secret is too short" }
        val tags = request.nodes.map(::nodeTag)
        val selectedTag = tags[selectedIndex]
        val uidPolicy = AndroidUidPolicy.build(
            androidUserIds = request.androidUserIds,
            excludedUids = request.excludedUids,
            appUid = request.appUid,
        )

        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", request.runtimeOptions.logLevel)
                put("timestamp", true)
            }
            put("dns", dns(request.mode, SELECTOR_TAG, request.runtimeOptions))
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "ebpf")
                    put("tag", "ebpf-in")
                    put("tc_priority", 1)
                    put("network", JsonArray(listOf(JsonPrimitive("tcp"), JsonPrimitive("udp"))))
                    putJsonObject("local") {
                        put("enabled", true)
                        put("data_plane", "cgroup")
                        put("dns_mode", "hijack")
                        putJsonArray("include_uid") {}
                        putJsonArray("include_uid_range") {}
                        put("exclude_uid", JsonArray(uidPolicy.excludeUids.map(::JsonPrimitive)))
                    }
                    if (request.hotspotProxyEnabled) {
                        putJsonObject("shared") {
                            put("enabled", true)
                            put("data_plane", "packet_rewrite")
                            put("dns_mode", "hijack")
                            put("android_tethering", "wifi")
                        }
                    }
                })
            }
            putJsonArray("outbounds") {
                request.nodes.forEachIndexed { index, node ->
                    add(
                        JsonObject(
                            node.outbound + ("tag" to JsonPrimitive(tags[index])),
                        ),
                    )
                }
                add(buildJsonObject {
                    put("type", "selector")
                    put("tag", SELECTOR_TAG)
                    put("outbounds", JsonArray(tags.map(::JsonPrimitive)))
                    put("default", selectedTag)
                })
                if (request.mode == ProxyMode.RULE) {
                    add(buildJsonObject {
                        put("type", "direct")
                        put("tag", DIRECT_OUTBOUND_TAG)
                    })
                    add(buildJsonObject {
                        put("type", FALLBACK_OUTBOUND_TYPE)
                        put("tag", FALLBACK_OUTBOUND_TAG)
                        put("primary", DIRECT_OUTBOUND_TAG)
                        put("fallback", SELECTOR_TAG)
                        put("connect_timeout", FALLBACK_CONNECT_TIMEOUT)
                    })
                }
            }
            put("route", route(request.mode, request.dataDirectory, SELECTOR_TAG))
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:9090")
                    put("secret", request.clashSecret)
                }
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("path", "${request.dataDirectory}/cache.db")
                }
            }
        }
        return JSON.encodeToString(root)
    }

    private fun dns(
        mode: ProxyMode,
        proxyOutboundTag: String,
        runtimeOptions: RuntimeConfigOptions,
    ): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            add(buildJsonObject {
                put("type", "https")
                put("tag", "local-dns")
                put("server", BOOTSTRAP_DNS_ADDRESS)
                put("server_port", 443)
                put("path", "/dns-query")
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", BOOTSTRAP_DNS_SERVER_NAME)
                }
            })
            add(buildJsonObject {
                put("type", "https")
                put("tag", "proxy-dns")
                put("server", "1.1.1.1")
                put("server_port", 443)
                put("path", "/dns-query")
                put("detour", proxyOutboundTag)
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", "cloudflare-dns.com")
                }
            })
        }
        if (mode == ProxyMode.RULE) {
            putJsonArray("rules") {
                add(buildJsonObject {
                    put("rule_set", JsonArray(listOf(JsonPrimitive(GEOSITE_CN_TAG))))
                    put("action", "route")
                    put("server", "local-dns")
                })
                add(buildJsonObject {
                    put("rule_set", JsonArray(listOf(JsonPrimitive(GEOSITE_NON_CN_TAG))))
                    put("action", "route")
                    put("server", "proxy-dns")
                })
                add(buildJsonObject {
                    put("action", "evaluate")
                    put("server", "local-dns")
                    put("tag", LOCAL_DNS_EVALUATION_TAG)
                    put("timeout", LOCAL_DNS_EVALUATION_TIMEOUT)
                })
                add(buildJsonObject {
                    put("match_response", LOCAL_DNS_EVALUATION_TAG)
                    put("rule_set", JsonArray(listOf(JsonPrimitive(GEOIP_CN_TAG))))
                    put("action", "respond")
                    put("race", true)
                })
                add(buildJsonObject {
                    put("action", "route")
                    put("server", "proxy-dns")
                    put("speculative", true)
                })
            }
        }
        if (mode == ProxyMode.RULE && runtimeOptions.reverseDnsMapping) put("reverse_mapping", true)
        if (runtimeOptions.optimisticDnsCache) {
            putJsonObject("optimistic") {
                put("enabled", true)
                put("timeout", "30m")
            }
        }
        put("final", "proxy-dns")
    }

    private fun route(mode: ProxyMode, dataDirectory: String, proxyOutboundTag: String): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        put("default_domain_resolver", "local-dns")
        putJsonArray("rules") {
            add(buildJsonObject {
                put("ip_cidr", JsonArray(listOf(JsonPrimitive("$BOOTSTRAP_DNS_ADDRESS/32"))))
                put("action", "direct")
            })
            add(buildJsonObject {
                put("port", 53)
                put("action", "hijack-dns")
            })
            add(buildJsonObject {
                put("ip_is_private", true)
                put("action", "direct")
            })
            add(buildJsonObject {
                put("ip_cidr", JsonArray(RESERVED_CIDRS.map(::JsonPrimitive)))
                put("action", "direct")
            })
            if (mode == ProxyMode.RULE) {
                add(buildJsonObject {
                    put("action", "sniff")
                    put("sniffer", JsonArray(SNIFFERS.map(::JsonPrimitive)))
                    put("timeout", "200ms")
                })
                add(buildJsonObject {
                    put("rule_set", JsonArray(listOf(JsonPrimitive(GEOSITE_CN_TAG))))
                    put("action", "direct")
                })
                add(buildJsonObject {
                    put("rule_set", JsonArray(listOf(JsonPrimitive(GEOSITE_NON_CN_TAG))))
                    put("action", "route")
                    put("outbound", proxyOutboundTag)
                })
                add(buildJsonObject {
                    put("rule_set", JsonArray(listOf(JsonPrimitive(GEOIP_CN_TAG))))
                    put("action", "route")
                    put("outbound", FALLBACK_OUTBOUND_TAG)
                })
            }
        }
        if (mode == ProxyMode.RULE) {
            putJsonArray("rule_set") {
                add(localRuleSet(GEOIP_CN_TAG, "$dataDirectory/rules/geoip-cn.srs"))
                add(localRuleSet(GEOSITE_CN_TAG, "$dataDirectory/rules/geosite-geolocation-cn.srs"))
                add(localRuleSet(GEOSITE_NON_CN_TAG, "$dataDirectory/rules/geosite-geolocation-!cn.srs"))
            }
        }
        put("final", proxyOutboundTag)
    }

    private fun localRuleSet(tag: String, path: String): JsonObject = buildJsonObject {
        put("type", "local")
        put("tag", tag)
        put("format", "binary")
        put("path", path)
    }

    companion object {
        const val SELECTOR_TAG = "AkihaLink"
        const val DIRECT_OUTBOUND_TAG = "akihalink-direct"
        const val FALLBACK_OUTBOUND_TAG = "akihalink-cn-fallback"
        const val FALLBACK_OUTBOUND_TYPE = "akihalink-fallback"
        private const val BOOTSTRAP_DNS_ADDRESS = "223.5.5.5"
        private const val BOOTSTRAP_DNS_SERVER_NAME = "dns.alidns.com"
        private const val GEOIP_CN_TAG = "geoip-cn"
        private const val GEOSITE_CN_TAG = "geosite-geolocation-cn"
        private const val GEOSITE_NON_CN_TAG = "geosite-geolocation-!cn"
        private const val LOCAL_DNS_EVALUATION_TAG = "local-cn-candidate"
        private const val LOCAL_DNS_EVALUATION_TIMEOUT = "1.2s"
        private const val FALLBACK_CONNECT_TIMEOUT = "1.5s"
        private val SNIFFERS = listOf("tls", "http", "quic")
        private val JSON = Json
        private val RESERVED_CIDRS = listOf(
            "0.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24",
            "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "64:ff9b::/96", "100::/64", "2001:db8::/32",
            "fc00::/7", "fe80::/10", "ff00::/8",
        )

        fun nodeTag(node: ProxyNode): String {
            return nodeTag(node.id)
        }

        fun nodeTag(nodeId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(nodeId.toByteArray(StandardCharsets.UTF_8))
                .take(10)
                .joinToString("") { "%02x".format(it) }
            return "node-$digest"
        }

    }
}

class SpeedTestConfigGenerator {
    fun generate(request: SpeedTestConfigRequest): String {
        require(request.nodes.isNotEmpty()) { "At least one node is required" }
        require(request.clashSecret.length >= 32) { "Clash API secret is too short" }

        val tags = request.nodes.map(SingBoxConfigGenerator::nodeTag)
        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", request.runtimeOptions.logLevel)
                put("timestamp", true)
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("type", "https")
                        put("tag", "local-dns")
                        put("server", BOOTSTRAP_DNS_ADDRESS)
                        put("server_port", 443)
                        put("path", "/dns-query")
                        putJsonObject("tls") {
                            put("enabled", true)
                            put("server_name", BOOTSTRAP_DNS_SERVER_NAME)
                        }
                    })
                }
                put("final", "local-dns")
            }
            putJsonArray("outbounds") {
                request.nodes.forEachIndexed { index, node ->
                    add(
                        JsonObject(
                            node.outbound + ("tag" to JsonPrimitive(tags[index])),
                        ),
                    )
                }
            }
            putJsonObject("route") {
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local-dns")
                put("final", tags.first())
            }
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:$CONTROLLER_PORT")
                    put("secret", request.clashSecret)
                }
            }
        }
        return JSON.encodeToString(root)
    }

    companion object {
        const val CONTROLLER_PORT = 19090
        private const val BOOTSTRAP_DNS_ADDRESS = "223.5.5.5"
        private const val BOOTSTRAP_DNS_SERVER_NAME = "dns.alidns.com"
        private val JSON = Json
    }
}
