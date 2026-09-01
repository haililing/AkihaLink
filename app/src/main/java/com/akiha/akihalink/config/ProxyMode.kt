package com.akiha.akihalink.config

enum class ProxyMode(val wireName: String) {
    GLOBAL("global"),
    RULE("rule"),
    DIRECT("direct"),
}
