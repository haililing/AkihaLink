package com.akiha.akihalink.ui

internal data class NodeDisplayIdentity(
    val flag: String,
    val countryCode: String,
    val countryName: String,
) {
    val label: String get() = "$flag $countryCode"
}

internal data class NodeDisplayDetails(
    val identity: NodeDisplayIdentity,
    val routeType: String?,
    val multiplier: String?,
) {
    val attributes: List<String> get() = listOfNotNull(routeType, multiplier)
}

internal fun nodeDisplayDetails(name: String): NodeDisplayDetails {
    val routeType = when {
        DEDICATED_ROUTE_PATTERN.containsMatchIn(name) -> "专线"
        name.contains("高速", ignoreCase = true) -> "高速"
        else -> null
    }
    val multiplier = MULTIPLIER_PATTERN.find(name)?.groupValues?.get(1)?.let { "${it}x" }
    return NodeDisplayDetails(nodeDisplayIdentity(name), routeType, multiplier)
}

internal fun nodeDisplayName(name: String): String {
    val details = nodeDisplayDetails(name)
    return (listOf(details.identity.countryName) + details.attributes).joinToString("|")
}

internal fun nodeDisplayIdentity(name: String): NodeDisplayIdentity {
    identityFromFlag(name)?.let { return it }
    val normalized = name.lowercase()
    return COUNTRY_ALIASES.firstOrNull { country ->
        country.aliases.any { alias ->
            if (alias.length == 2 && alias.all { it in 'A'..'Z' }) {
                Regex("(?:^|[^A-Za-z])${Regex.escape(alias)}(?:[^A-Za-z]|$)")
                    .containsMatchIn(name)
            } else if (alias.all(Char::isLetter) && alias.all { it.code < 128 }) {
                Regex("(?:^|[^a-z])${Regex.escape(alias)}(?:[^a-z]|$)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(name)
            } else {
                normalized.contains(alias.lowercase())
            }
        }
    }?.let { NodeDisplayIdentity(flagFor(it.flagCode), it.displayCode, it.countryName) }
        ?: NodeDisplayIdentity("🌐", "UN", "其他")
}

private fun identityFromFlag(name: String): NodeDisplayIdentity? {
    val codePoints = name.codePoints().toArray()
    for (index in 0 until codePoints.lastIndex) {
        val first = codePoints[index]
        val second = codePoints[index + 1]
        if (first in REGIONAL_A..REGIONAL_Z && second in REGIONAL_A..REGIONAL_Z) {
            val code = buildString(2) {
                append(('A'.code + first - REGIONAL_A).toChar())
                append(('A'.code + second - REGIONAL_A).toChar())
            }
            val flag = String(Character.toChars(first)) + String(Character.toChars(second))
            val country = COUNTRY_ALIASES.firstOrNull { it.flagCode == code }
            return NodeDisplayIdentity(flag, country?.displayCode ?: code, country?.countryName ?: code)
        }
    }
    return null
}

private fun flagFor(code: String): String = buildString {
    code.uppercase().take(2).forEach { letter ->
        append(String(Character.toChars(REGIONAL_A + letter.code - 'A'.code)))
    }
}

private data class CountryAliases(
    val flagCode: String,
    val countryName: String,
    val displayCode: String = flagCode,
    val aliases: List<String>,
)

private val COUNTRY_ALIASES = listOf(
    CountryAliases("HK", "香港", aliases = listOf("HK", "Hong Kong", "香港", "港区", "港區")),
    CountryAliases("TW", "台湾", aliases = listOf("TW", "Taiwan", "台湾", "台灣", "臺灣", "台北", "臺北")),
    CountryAliases("JP", "日本", aliases = listOf("JP", "Japan", "日本", "东京", "東京", "大阪")),
    CountryAliases("SG", "新加坡", aliases = listOf("SG", "Singapore", "新加坡", "狮城", "獅城")),
    CountryAliases("US", "美国", aliases = listOf("US", "USA", "United States", "美国", "美國", "洛杉矶", "洛杉磯", "西雅图", "西雅圖", "圣何塞", "聖何塞", "纽约", "紐約")),
    CountryAliases("KR", "韩国", aliases = listOf("KR", "Korea", "South Korea", "韩国", "韓國", "首尔", "首爾")),
    CountryAliases("GB", "英国", "UK", listOf("UK", "GB", "United Kingdom", "Britain", "英国", "英國", "伦敦", "倫敦")),
    CountryAliases("DE", "德国", aliases = listOf("DE", "Germany", "德国", "德國", "法兰克福", "法蘭克福")),
    CountryAliases("FR", "法国", aliases = listOf("FR", "France", "法国", "法國", "巴黎")),
    CountryAliases("CA", "加拿大", aliases = listOf("CA", "Canada", "加拿大", "多伦多", "多倫多")),
    CountryAliases("AU", "澳大利亚", aliases = listOf("AU", "Australia", "澳大利亚", "澳大利亞", "澳洲", "悉尼")),
    CountryAliases("RU", "俄罗斯", aliases = listOf("RU", "Russia", "俄罗斯", "俄羅斯", "莫斯科")),
    CountryAliases("IN", "印度", aliases = listOf("IN", "India", "印度", "孟买", "孟買")),
    CountryAliases("NL", "荷兰", aliases = listOf("NL", "Netherlands", "Holland", "荷兰", "荷蘭")),
    CountryAliases("TR", "土耳其", aliases = listOf("TR", "Turkey", "Türkiye", "土耳其")),
    CountryAliases("BR", "巴西", aliases = listOf("BR", "Brazil", "巴西")),
    CountryAliases("MY", "马来西亚", aliases = listOf("MY", "Malaysia", "马来西亚", "馬來西亞")),
    CountryAliases("TH", "泰国", aliases = listOf("TH", "Thailand", "泰国", "泰國")),
    CountryAliases("VN", "越南", aliases = listOf("VN", "Vietnam", "越南")),
    CountryAliases("PH", "菲律宾", aliases = listOf("PH", "Philippines", "菲律宾", "菲律賓")),
    CountryAliases("ID", "印度尼西亚", aliases = listOf("ID", "Indonesia", "印度尼西亚", "印度尼西亞", "印尼")),
    CountryAliases("AE", "阿联酋", aliases = listOf("AE", "UAE", "United Arab Emirates", "阿联酋", "阿聯酋", "迪拜", "杜拜")),
    CountryAliases("CH", "瑞士", aliases = listOf("CH", "Switzerland", "瑞士")),
    CountryAliases("SE", "瑞典", aliases = listOf("SE", "Sweden", "瑞典")),
    CountryAliases("NO", "挪威", aliases = listOf("NO", "Norway", "挪威")),
    CountryAliases("FI", "芬兰", aliases = listOf("FI", "Finland", "芬兰", "芬蘭")),
    CountryAliases("DK", "丹麦", aliases = listOf("DK", "Denmark", "丹麦", "丹麥")),
    CountryAliases("ES", "西班牙", aliases = listOf("ES", "Spain", "西班牙")),
    CountryAliases("IT", "意大利", aliases = listOf("IT", "Italy", "意大利", "義大利")),
    CountryAliases("PL", "波兰", aliases = listOf("PL", "Poland", "波兰", "波蘭")),
    CountryAliases("CN", "中国", aliases = listOf("CN", "China", "中国", "中國", "大陆", "大陸")),
)

private val DEDICATED_ROUTE_PATTERN = Regex("专线|專線|IPLC|IEPL", RegexOption.IGNORE_CASE)
private val MULTIPLIER_PATTERN = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*[xXｘ×倍]")

private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF
