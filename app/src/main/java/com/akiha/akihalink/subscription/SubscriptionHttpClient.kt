package com.akiha.akihalink.subscription

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class SubscriptionUserInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
    val expirationProvided: Boolean = false,
    val startedAt: Long? = null,
)

data class SubscriptionDownload(
    val content: ByteArray,
    val userInfo: SubscriptionUserInfo?,
    val refreshIntervalHours: Long?,
)

internal fun parseProfileUpdateInterval(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.takeIf { it > 0 }

internal fun parseSubscriptionUserInfo(header: String?): SubscriptionUserInfo? {
    val values = header
        ?.split(USER_INFO_SEPARATOR)
        ?.mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = normalizeMetadataKey(item.substring(0, separator))
            val value = item.substring(separator + 1).trim().trim('"', '\'')
            if (value.isEmpty()) return@mapNotNull null
            key to value
        }
        ?.toMap()
        .orEmpty()
    if (values.keys.none { it in USER_INFO_KEYS }) return null

    return SubscriptionUserInfo(
        uploadBytes = values["upload"].toNonNegativeLongOrNull(),
        downloadBytes = values["download"].toNonNegativeLongOrNull(),
        totalBytes = values["total"].toNonNegativeLongOrNull(),
        expiresAt = parseSubscriptionTimestamp(values["expire"], allowZero = true),
        expirationProvided = "expire" in values,
        startedAt = START_TIME_KEYS.firstNotNullOfOrNull { key ->
            parseSubscriptionTimestamp(values[key])
        },
    )
}

internal fun parseSubscriptionUserInfoFromContent(content: ByteArray): SubscriptionUserInfo? =
    subscriptionMetadataLines(content).firstNotNullOfOrNull { line ->
        line.metadataValue(SUBSCRIPTION_USER_INFO_KEY)?.let(::parseSubscriptionUserInfo)
    }

internal fun parseSubscriptionStartedAtFromContent(content: ByteArray): Long? =
    subscriptionMetadataLines(content).firstNotNullOfOrNull { line ->
        val entry = line.metadataEntry() ?: return@firstNotNullOfOrNull null
        if (entry.first !in START_TIME_KEYS) return@firstNotNullOfOrNull null
        parseSubscriptionTimestamp(entry.second)
    }

internal fun mergeSubscriptionUserInfo(
    primary: SubscriptionUserInfo?,
    fallback: SubscriptionUserInfo?,
    supplementalStartedAt: Long? = null,
): SubscriptionUserInfo? {
    if (primary == null && fallback == null && supplementalStartedAt == null) return null
    val expirationProvided = primary?.expirationProvided == true || fallback?.expirationProvided == true
    val expiresAt = when {
        primary?.expirationProvided == true -> primary.expiresAt
        fallback?.expirationProvided == true -> fallback.expiresAt
        else -> primary?.expiresAt ?: fallback?.expiresAt
    }
    return SubscriptionUserInfo(
        uploadBytes = primary?.uploadBytes ?: fallback?.uploadBytes,
        downloadBytes = primary?.downloadBytes ?: fallback?.downloadBytes,
        totalBytes = primary?.totalBytes ?: fallback?.totalBytes,
        expiresAt = expiresAt,
        expirationProvided = expirationProvided,
        startedAt = primary?.startedAt ?: supplementalStartedAt ?: fallback?.startedAt,
    )
}

class SubscriptionHttpClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (!chain.request().url.isHttps) {
                throw IOException("订阅重定向必须使用 HTTPS")
            }
            chain.proceed(chain.request())
        }
        .build()

    suspend fun download(url: String): SubscriptionDownload = withContext(Dispatchers.IO) {
        val requestUrl = url.trim().toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?: throw SubscriptionParseException("订阅地址必须是有效的 HTTPS 地址")
        val request = Request.Builder()
            .url(requestUrl)
            // A number of subscription services only expose their full metadata to a recognized client family.
            // The parser accepts Clash YAML as well as sing-box JSON and URI lists, so this compatibility UA is safe.
            .header("User-Agent", SUBSCRIPTION_USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SubscriptionParseException("Subscription request failed with HTTP ${response.code}")
            }
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > SubscriptionParser.MAX_RESPONSE_BYTES) {
                throw SubscriptionParseException("Subscription exceeds 10 MB")
            }
            val output = ByteArrayOutputStream(
                declaredLength.takeIf { it in 1..SubscriptionParser.MAX_RESPONSE_BYTES.toLong() }
                    ?.toInt() ?: 8_192,
            )
            val buffer = ByteArray(8_192)
            body.byteStream().use { stream ->
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > SubscriptionParser.MAX_RESPONSE_BYTES) {
                        throw SubscriptionParseException("Subscription exceeds 10 MB")
                    }
                    output.write(buffer, 0, read)
                }
            }
            val content = output.toByteArray()
            val headerUserInfo = response.headers.values(SUBSCRIPTION_USER_INFO_HEADER)
                .asReversed()
                .firstNotNullOfOrNull(::parseSubscriptionUserInfo)
            val contentUserInfo = parseSubscriptionUserInfoFromContent(content)
            val standaloneStartedAt = START_TIME_HEADER_NAMES.firstNotNullOfOrNull { headerName ->
                parseSubscriptionTimestamp(response.header(headerName))
            } ?: parseSubscriptionStartedAtFromContent(content)
            SubscriptionDownload(
                content = content,
                userInfo = mergeSubscriptionUserInfo(
                    primary = headerUserInfo,
                    fallback = contentUserInfo,
                    supplementalStartedAt = standaloneStartedAt,
                ),
                refreshIntervalHours = parseProfileUpdateInterval(
                    response.header(PROFILE_UPDATE_INTERVAL_HEADER),
                ),
            )
        }
    }

    private companion object {
        const val SUBSCRIPTION_USER_INFO_HEADER = "subscription-userinfo"
        const val PROFILE_UPDATE_INTERVAL_HEADER = "profile-update-interval"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 45L
    }
}

internal const val SUBSCRIPTION_USER_AGENT = "clash.meta"

private const val SUBSCRIPTION_USER_INFO_KEY = "subscription_userinfo"
private const val MAX_METADATA_PREFIX_BYTES = 64 * 1_024
private val USER_INFO_SEPARATOR = Regex("[;,&]")
private val START_TIME_KEYS = listOf(
    "start",
    "started_at",
    "start_time",
    "start_date",
    "subscription_start",
    "subscription_started_at",
    "subscription_start_time",
    "subscription_start_date",
    "purchase_time",
    "purchased_at",
    "activated_at",
    "effective_at",
    "created_at",
)
private val USER_INFO_KEYS = setOf("upload", "download", "total", "expire") + START_TIME_KEYS
private val START_TIME_HEADER_NAMES = listOf(
    "subscription-start",
    "subscription-started-at",
    "subscription-start-time",
    "subscription-start-date",
    "profile-start-time",
    "profile-start-date",
    "x-subscription-start",
    "x-subscription-start-time",
)
private val LOCAL_DATE_TIME_FORMATTERS = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
)

private fun normalizeMetadataKey(value: String): String = value
    .trim()
    .trim('"', '\'')
    .replace(CAMEL_CASE_BOUNDARY, "$1_$2")
    .lowercase(Locale.ROOT)
    .replace('-', '_')

private fun String?.toNonNegativeLongOrNull(): Long? =
    this?.toLongOrNull()?.takeIf { it >= 0 }

private fun parseSubscriptionTimestamp(value: String?, allowZero: Boolean = false): Long? {
    val normalized = value?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
    normalized.toLongOrNull()?.let { numeric ->
        if (numeric == 0L) return 0L.takeIf { allowZero }
        if (numeric < 0) return null
        return if (numeric >= EPOCH_MILLIS_THRESHOLD) {
            numeric
        } else {
            numeric.takeIf { it <= Long.MAX_VALUE / 1_000 }?.times(1_000)
        }
    }
    return parseDateTimestamp(normalized)
}

private fun parseDateTimestamp(value: String): Long? {
    try {
        return Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // Try the other common provider formats below.
    }
    try {
        return OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        // Continue with local date/time formats.
    }
    LOCAL_DATE_TIME_FORMATTERS.forEach { formatter ->
        try {
            return LocalDateTime.parse(value, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Try the next format.
        }
    }
    return try {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun subscriptionMetadataLines(content: ByteArray): Sequence<String> {
    val length = content.size.coerceAtMost(MAX_METADATA_PREFIX_BYTES)
    return String(content, 0, length, Charsets.UTF_8).lineSequence()
}

private fun String.metadataEntry(): Pair<String, String>? {
    val uncommented = trim()
        .removePrefix("//")
        .removePrefix("#")
        .removePrefix(";")
        .trim()
    val colon = uncommented.indexOf(':')
    val equals = uncommented.indexOf('=')
    val separator = listOf(colon, equals).filter { it > 0 }.minOrNull() ?: return null
    val key = normalizeMetadataKey(uncommented.substring(0, separator))
    val value = uncommented.substring(separator + 1).trim()
    return (key to value).takeIf { value.isNotEmpty() }
}

private fun String.metadataValue(expectedKey: String): String? =
    metadataEntry()?.takeIf { it.first == expectedKey }?.second

private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
private val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
