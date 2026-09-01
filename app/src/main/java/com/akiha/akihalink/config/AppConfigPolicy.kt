package com.akiha.akihalink.config

internal object AppConfigPolicy {
    val runtimeOptions = RuntimeConfigOptions(
        logLevel = "warn",
        reverseDnsMapping = true,
        optimisticDnsCache = true,
    )
}
