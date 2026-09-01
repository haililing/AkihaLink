package com.akiha.akihalink.subscription

import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class SubscriptionParser {
    fun parse(content: ByteArray, subscriptionId: String): ParseResult {
        if (content.size > MAX_RESPONSE_BYTES) {
            throw SubscriptionParseException("Subscription exceeds 10 MB")
        }
        val text = content.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw SubscriptionParseException("Subscription is empty")

        val candidates = when {
            looksLikeSingBoxJson(text) -> parseSingBox(text)
            looksLikeClashYaml(text) -> parseClash(text)
            else -> parseUriList(decodeWholeBase64IfNeeded(text))
        }
        val accepted = ArrayList<ProxyNode>(minOf(candidates.size, MAX_NODES))
        val fingerprints = HashSet<String>(minOf(candidates.size, MAX_NODES))
        val skipReasons = linkedMapOf<String, Int>()
        var skipped = 0
        for ((index, candidate) in candidates.withIndex()) {
            if (accepted.size == MAX_NODES) {
                skipped += candidates.size - index
                break
            }
            try {
                val node = normalize(candidate.first, candidate.second, subscriptionId)
                if (fingerprints.add(node.fingerprint)) accepted += node else skipped++
            } catch (error: Exception) {
                skipped++
                val reason = error.message?.trim()?.take(120).orEmpty().ifBlank { "Invalid node options" }
                skipReasons[reason] = (skipReasons[reason] ?: 0) + 1
            }
        }
        if (accepted.isEmpty()) {
            val detail = skipReasons.entries.maxByOrNull { it.value }?.key
            throw SubscriptionParseException(
                "Subscription contains no supported nodes" + detail?.let { ": $it" }.orEmpty(),
            )
        }
        return ParseResult(accepted, skipped, skipReasons)
    }

    private fun looksLikeSingBoxJson(text: String): Boolean = runCatching {
        JSON.parseToJsonElement(text).jsonObject["outbounds"] is JsonArray
    }.getOrDefault(false)

    private fun looksLikeClashYaml(text: String): Boolean =
        Regex("(?m)^\\s*proxies\\s*:").containsMatchIn(text)

    private fun parseSingBox(text: String): List<Pair<String, JsonObject>> {
        val root = JSON.parseToJsonElement(text).jsonObject
        return root["outbounds"]?.jsonArray.orEmpty().mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            val type = outbound.string("type")?.lowercase() ?: return@mapNotNull null
            if (type !in SUPPORTED_PROTOCOLS) return@mapNotNull null
            (outbound.string("tag") ?: "$type node") to sanitize(outbound, type)
        }
    }

    private fun parseClash(text: String): List<Pair<String, JsonObject>> {
        val settings = LoadSettings.builder()
            .setLabel("AkihaLink subscription")
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setAllowNonScalarKeys(false)
            .setMaxAliasesForCollections(MAX_YAML_ALIASES)
            .setCodePointLimit(MAX_RESPONSE_BYTES)
            .build()
        val root = try {
            Load(settings).loadFromReader(SurrogateSafeReader(text)) as? Map<*, *>
                ?: throw SubscriptionParseException("Invalid Clash YAML")
        } catch (error: SubscriptionParseException) {
            throw error
        } catch (error: Exception) {
            throw SubscriptionParseException("Invalid Clash YAML", error)
        }
        val proxies = root["proxies"] as? List<*> ?: return emptyList()
        return proxies.mapNotNull { raw ->
            runCatching { clashNode(raw as Map<*, *>) }.getOrNull()
        }
    }

    private fun clashNode(raw: Map<*, *>): Pair<String, JsonObject> {
        val type = when (raw.text("type").lowercase()) {
            "ss" -> "shadowsocks"
            "hy2", "hysteria-2" -> "hysteria2"
            else -> raw.text("type").lowercase()
        }
        require(type in SUPPORTED_PROTOCOLS)
        val name = raw.text("name")
        val server = raw.text("server")
        val port = raw.number("port")
        val outbound = buildJsonObject {
            put("type", type)
            put("server", server)
            put("server_port", port)
            when (type) {
                "shadowsocks" -> {
                    put("method", raw.text("cipher"))
                    put("password", raw.text("password"))
                    raw.optionalText("plugin")?.let { put("plugin", it) }
                    raw.optionalText("plugin-opts")?.let { put("plugin_opts", it) }
                }
                "vmess" -> {
                    put("uuid", raw.text("uuid"))
                    put("security", raw.optionalText("cipher") ?: "auto")
                    raw.optionalNumber("alterId")?.let { put("alter_id", it) }
                }
                "vless" -> {
                    put("uuid", raw.text("uuid"))
                    raw.optionalText("flow")?.let { put("flow", it) }
                }
                "trojan" -> put("password", raw.text("password"))
                "hysteria2" -> {
                    put("password", raw.optionalText("password") ?: raw.text("auth"))
                    raw.optionalNumber("up")?.let { put("up_mbps", it) }
                    raw.optionalNumber("down")?.let { put("down_mbps", it) }
                    raw.optionalText("obfs")?.let { obfsType ->
                        putJsonObject("obfs") {
                            put("type", obfsType)
                            raw.optionalText("obfs-password")?.let { put("password", it) }
                        }
                    }
                }
                "tuic" -> {
                    put("uuid", raw.text("uuid"))
                    put("password", raw.text("password"))
                    raw.optionalText("congestion-controller")?.let { put("congestion_control", it) }
                    raw.optionalText("udp-relay-mode")?.let { put("udp_relay_mode", it) }
                }
                "anytls" -> {
                    put("password", raw.text("password"))
                    raw.optionalNumber("idle-session-check-interval")
                        ?.let { put("idle_session_check_interval", "${it}s") }
                    raw.optionalNumber("idle-session-timeout")
                        ?.let { put("idle_session_timeout", "${it}s") }
                    raw.optionalNumber("min-idle-session")?.let { put("min_idle_session", it) }
                }
            }
            clashTls(raw, type in TLS_REQUIRED_PROTOCOLS)?.let { put("tls", it) }
            clashTransport(raw)?.let { put("transport", it) }
            if (type in setOf("shadowsocks", "vmess")) {
                clashMultiplex(raw)?.let { put("multiplex", it) }
            }
        }
        return name to outbound
    }

    private fun clashMultiplex(raw: Map<*, *>): JsonObject? {
        val options = raw["smux"] as? Map<*, *> ?: return null
        return buildJsonObject {
            options.optionalBoolean("enabled")?.let { put("enabled", it) }
            options.optionalText("protocol")?.let { put("protocol", it) }
            options.optionalNumber("max-connections")?.let { put("max_connections", it) }
            options.optionalNumber("min-streams")?.let { put("min_streams", it) }
            options.optionalNumber("max-streams")?.let { put("max_streams", it) }
            options.optionalBoolean("padding")?.let { put("padding", it) }
        }
    }

    private fun clashTls(raw: Map<*, *>, force: Boolean): JsonObject? {
        val reality = raw["reality-opts"] as? Map<*, *>
        val tlsEnabled = raw.optionalBoolean("tls") == true || reality != null ||
            raw.optionalText("security") in setOf("tls", "reality")
        if (!tlsEnabled && !force) return null
        return buildJsonObject {
            put("enabled", true)
            (raw.optionalText("servername") ?: raw.optionalText("sni"))?.let { put("server_name", it) }
            raw.optionalBoolean("skip-cert-verify")?.let { put("insecure", it) }
            (raw["alpn"] as? List<*>)?.map { it.toString() }?.let { values ->
                putJsonArray("alpn") { values.forEach { add(JsonPrimitive(it)) } }
            }
            raw.optionalText("client-fingerprint")?.let { fingerprint ->
                putJsonObject("utls") { put("enabled", true); put("fingerprint", fingerprint) }
            }
            reality?.let {
                putJsonObject("reality") {
                    put("enabled", true)
                    (it["public-key"] ?: it["public_key"])?.toString()?.let { value -> put("public_key", value) }
                    (it["short-id"] ?: it["short_id"])?.toString()?.let { value -> put("short_id", value) }
                }
            }
        }
    }

    private fun clashTransport(raw: Map<*, *>): JsonObject? {
        val network = raw.optionalText("network") ?: return null
        if (network !in TRANSPORT_TYPES) return null
        return buildJsonObject {
            put("type", network)
            when (network) {
                "ws" -> {
                    val opts = raw["ws-opts"] as? Map<*, *>
                    opts?.optionalText("path")?.let { put("path", it) }
                    opts?.get("headers")?.let { put("headers", anyToJson(it)) }
                }
                "grpc" -> {
                    val opts = raw["grpc-opts"] as? Map<*, *>
                    (opts?.optionalText("grpc-service-name") ?: raw.optionalText("serviceName"))
                        ?.let { put("service_name", it) }
                }
                "http", "httpupgrade" -> raw.optionalText("path")?.let { put("path", it) }
            }
        }
    }

    private fun parseUriList(text: String): List<Pair<String, JsonObject>> = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line -> runCatching { parseUri(line) }.getOrNull() }
        .toList()

    private fun parseUri(value: String): Pair<String, JsonObject> {
        if (value.startsWith("vmess://", ignoreCase = true)) return parseVmess(value)
        if (value.startsWith("ss://", ignoreCase = true)) return parseShadowsocks(value)
        val uri = URI(value)
        val type = uri.scheme?.lowercase()?.replace("hy2", "hysteria2")
            ?: throw IllegalArgumentException("Missing protocol")
        require(type in SUPPORTED_PROTOCOLS - "shadowsocks" - "vmess")
        val query = parseQuery(uri.rawQuery)
        val password = decode(uri.rawUserInfo ?: "")
        val host = uri.host ?: throw IllegalArgumentException("Missing server")
        val port = uri.port.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Missing port")
        val name = decode(uri.rawFragment ?: "$type $host")
        val outbound = buildJsonObject {
            put("type", type)
            put("server", host)
            put("server_port", port)
            when (type) {
                "vless" -> { put("uuid", password); query["flow"]?.let { put("flow", it) } }
                "trojan" -> put("password", password)
                "anytls" -> {
                    put("password", password)
                    (query["idle_session_check_interval"] ?: query["idle-session-check-interval"])
                        ?.let { put("idle_session_check_interval", it) }
                    (query["idle_session_timeout"] ?: query["idle-session-timeout"])
                        ?.let { put("idle_session_timeout", it) }
                    (query["min_idle_session"] ?: query["min-idle-session"])
                        ?.toIntOrNull()?.let { put("min_idle_session", it) }
                    (query["disable_reuse"] ?: query["disable-reuse"])
                        ?.toBooleanStrictOrNull()?.let { put("disable_reuse", it) }
                }
                "hysteria2" -> {
                    put("password", password)
                    query["obfs"]?.let { obfs ->
                        putJsonObject("obfs") {
                            put("type", obfs)
                            query["obfs-password"]?.let { put("password", it) }
                        }
                    }
                }
                "tuic" -> {
                    val parts = password.split(':', limit = 2)
                    put("uuid", parts.first())
                    put("password", parts.getOrElse(1) { query["password"].orEmpty() })
                    query["congestion_control"]?.let { put("congestion_control", it) }
                    query["udp_relay_mode"]?.let { put("udp_relay_mode", it) }
                }
            }
            uriTls(query, type in TLS_REQUIRED_PROTOCOLS)?.let { put("tls", it) }
            uriTransport(query)?.let { put("transport", it) }
        }
        return name to outbound
    }

    private fun parseVmess(value: String): Pair<String, JsonObject> {
        val raw = decodeBase64(value.substringAfter("vmess://"))
        val source = JSON.parseToJsonElement(raw).jsonObject
        val host = source.string("add") ?: throw IllegalArgumentException("Missing server")
        val port = source.string("port")?.toIntOrNull() ?: source["port"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Missing port")
        val outbound = buildJsonObject {
            put("type", "vmess")
            put("server", host)
            put("server_port", port)
            put("uuid", source.string("id") ?: throw IllegalArgumentException("Missing UUID"))
            put("security", source.string("scy") ?: "auto")
            source.string("aid")?.toIntOrNull()?.let { put("alter_id", it) }
            val query = mapOf(
                "security" to (source.string("tls") ?: ""),
                "sni" to (source.string("sni") ?: source.string("host") ?: ""),
                "type" to (source.string("net") ?: ""),
                "path" to (source.string("path") ?: ""),
                "host" to (source.string("host") ?: ""),
            )
            uriTls(query)?.let { put("tls", it) }
            uriTransport(query)?.let { put("transport", it) }
        }
        return (source.string("ps") ?: "VMess $host") to outbound
    }

    private fun parseShadowsocks(value: String): Pair<String, JsonObject> {
        var body = value.substringAfter("ss://")
        val fragment = body.substringAfter('#', "")
        body = body.substringBefore('#').substringBefore('?')
        if (!body.contains('@')) body = decodeBase64(body)
        val user = body.substringBeforeLast('@')
        val endpoint = body.substringAfterLast('@')
        val credentials = if (user.contains(':')) decode(user) else decodeBase64(user)
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        val endpointUri = URI("ss://$endpoint")
        val host = endpointUri.host ?: throw IllegalArgumentException("Missing server")
        val port = endpointUri.port.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Missing port")
        return decode(fragment.ifEmpty { "Shadowsocks $host" }) to buildJsonObject {
            put("type", "shadowsocks")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
    }

    private fun uriTls(query: Map<String, String>, force: Boolean = false): JsonObject? {
        val security = query["security"]
        if (!force && security !in setOf("tls", "reality") && query["tls"] != "1") return null
        return buildJsonObject {
            put("enabled", true)
            (query["sni"] ?: query["serverName"] ?: query["peer"])?.takeIf { it.isNotEmpty() }
                ?.let { put("server_name", it) }
            if (query["allowInsecure"] == "1" || query["insecure"] == "1") put("insecure", true)
            query["alpn"]?.split(',')?.filter { it.isNotBlank() }?.let { values ->
                putJsonArray("alpn") { values.forEach { add(JsonPrimitive(it)) } }
            }
            query["fp"]?.let { putJsonObject("utls") { put("enabled", true); put("fingerprint", it) } }
            if (security == "reality") putJsonObject("reality") {
                put("enabled", true)
                query["pbk"]?.let { put("public_key", it) }
                query["sid"]?.let { put("short_id", it) }
            }
        }
    }

    private fun uriTransport(query: Map<String, String>): JsonObject? {
        val type = query["type"]?.takeIf { it in TRANSPORT_TYPES } ?: return null
        return buildJsonObject {
            put("type", type)
            when (type) {
                "ws" -> {
                    query["path"]?.let { put("path", it) }
                    query["host"]?.let { putJsonObject("headers") { put("Host", it) } }
                }
                "grpc" -> (query["serviceName"] ?: query["service_name"])?.let { put("service_name", it) }
                "http", "httpupgrade" -> query["path"]?.let { put("path", it) }
            }
        }
    }

    private fun sanitize(source: JsonObject, type: String): JsonObject = buildJsonObject {
        val allowed = COMMON_FIELDS + PROTOCOL_FIELDS.getValue(type)
        source.forEach { (key, value) -> if (key in allowed) put(key, value) }
        put("type", type)
    }

    private fun normalize(name: String, raw: JsonObject, subscriptionId: String): ProxyNode {
        val type = raw.string("type") ?: error("Missing type")
        val server = raw.string("server")?.takeIf { it.isNotBlank() } ?: error("Missing server")
        val port = raw["server_port"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 }
            ?: error("Invalid port")
        validateNode(raw, type)
        val outbound = JsonObject(raw.filterKeys { it != "tag" })
        val fingerprint = sha256(canonicalJson(outbound))
        return ProxyNode(
            id = "$subscriptionId:$fingerprint",
            subscriptionId = subscriptionId,
            fingerprint = fingerprint,
            name = name.trim().take(120).ifEmpty { "$type $server" },
            protocol = type,
            endpoint = "${server.take(96)}:$port",
            outbound = outbound,
        )
    }

    private fun validateNode(raw: JsonObject, type: String) {
        fun requiredString(key: String): String = raw.string(key)?.takeIf { it.isNotBlank() }
            ?: error("Missing $key")
        when (type) {
            "shadowsocks" -> {
                requiredString("method")
                requiredString("password")
            }
            "vmess", "vless" -> UUID.fromString(requiredString("uuid"))
            "trojan", "hysteria2", "anytls" -> requiredString("password")
            "tuic" -> {
                UUID.fromString(requiredString("uuid"))
                requiredString("password")
            }
            else -> error("Unsupported protocol")
        }
        raw["tls"]?.let { require(it is JsonObject) { "Invalid TLS options" } }
        if (type in TLS_REQUIRED_PROTOCOLS) {
            val tls = raw["tls"] as? JsonObject ?: error("Missing TLS options")
            require(tls["enabled"]?.jsonPrimitive?.booleanOrNull == true) {
                "TLS must be enabled"
            }
        }
        raw["multiplex"]?.let { require(it is JsonObject) { "Invalid multiplex options" } }
        raw["transport"]?.let { transport ->
            require(transport is JsonObject) { "Invalid transport options" }
            require(transport.string("type") in TRANSPORT_TYPES) { "Unsupported transport" }
        }
    }

    private fun decodeWholeBase64IfNeeded(text: String): String {
        if (text.contains("://")) return text
        return runCatching { decodeBase64(text.filterNot(Char::isWhitespace)) }
            .getOrNull()
            ?.takeIf { it.contains("://") }
            ?: text
    }

    private fun decodeBase64(value: String): String {
        val clean = value.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&')
        .filter { it.isNotBlank() }
        .associate { part -> decode(part.substringBefore('=')) to decode(part.substringAfter('=', "")) }

    private fun decode(value: String): String = URLDecoder.decode(
        value.replace("+", "%2B"),
        StandardCharsets.UTF_8,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
            "${JsonPrimitive(it.key)}:${canonicalJson(it.value)}"
        }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> element.toString()
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun Map<*, *>.text(key: String): String = optionalText(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing $key")
    private fun Map<*, *>.optionalText(key: String): String? = this[key]?.toString()
    private fun Map<*, *>.number(key: String): Int = optionalNumber(key) ?: error("Missing $key")
    private fun Map<*, *>.optionalNumber(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull()
    }
    private fun Map<*, *>.optionalBoolean(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        else -> value?.toString()?.toBooleanStrictOrNull()
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to anyToJson(it.value) })
        is List<*> -> buildJsonArray { value.forEach { add(anyToJson(it)) } }
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        const val MAX_NODES = 2_000
        private const val MAX_YAML_ALIASES = 32
        val SUPPORTED_PROTOCOLS = setOf(
            "shadowsocks", "vmess", "vless", "trojan", "hysteria2", "tuic", "anytls",
        )
        private val TRANSPORT_TYPES = setOf("http", "ws", "quic", "grpc", "httpupgrade")
        private val TLS_REQUIRED_PROTOCOLS = setOf("hysteria2", "tuic", "anytls")
        private val COMMON_FIELDS = setOf("type", "server", "server_port", "network", "tls", "transport", "multiplex")
        private val PROTOCOL_FIELDS = mapOf(
            "shadowsocks" to setOf("method", "password", "plugin", "plugin_opts", "udp_over_tcp"),
            "vmess" to setOf("uuid", "security", "alter_id", "global_padding", "authenticated_length", "packet_encoding"),
            "vless" to setOf("uuid", "flow", "packet_encoding"),
            "trojan" to setOf("password"),
            "hysteria2" to setOf("server_ports", "hop_interval", "hop_interval_max", "up_mbps", "down_mbps", "obfs", "password", "bbr_profile"),
            "tuic" to setOf("uuid", "password", "congestion_control", "udp_relay_mode", "udp_over_stream", "zero_rtt_handshake", "heartbeat"),
            "anytls" to setOf("password", "client_name", "idle_session_check_interval", "idle_session_timeout", "min_idle_session", "disable_reuse"),
        )
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = false }
    }
}

private class SurrogateSafeReader(text: String) : Reader() {
    private val delegate = StringReader(text)

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // SnakeYAML needs one spare character when a chunk ends with a UTF-16 high surrogate.
        val safeLength = if (length > 1 && offset + length == buffer.size) length - 1 else length
        return delegate.read(buffer, offset, safeLength)
    }

    override fun close() = delegate.close()
}
