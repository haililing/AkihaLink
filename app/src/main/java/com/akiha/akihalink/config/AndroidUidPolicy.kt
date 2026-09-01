package com.akiha.akihalink.config

data class UidPolicySummary(
    val systemUids: Map<String, Int> = emptyMap(),
    val includeUidRanges: List<String> = emptyList(),
    val androidUserCount: Int = 0,
    val effectiveExcludedUidCount: Int = 0,
    val sdkSandboxDerivedExclusionCount: Int = 0,
    val sdkSandboxExclusionsDerived: Boolean = true,
    val primaryUidAndSdkSandboxDirectOnly: Boolean = true,
    val isolatedProcessesAlwaysProxied: Boolean = true,
    val androidSystemResolverEnabled: Boolean = true,
    val androidSystemResolverPolicy: String = "verified_root_netd_dns_only",
    val androidSystemResolverDnsMayBeProxied: Boolean = true,
)

internal data class AndroidUidPolicy(
    val includeUids: List<Int>,
    val includeUidRanges: List<String>,
    val excludeUids: List<Int>,
    val summary: UidPolicySummary,
) {
    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val FIRST_APPLICATION_UID = 10_000
        private const val LAST_APPLICATION_UID = 19_999
        private const val LAST_SDK_SANDBOX_UID = 29_999
        private const val FIRST_ISOLATED_UID = 90_000
        private const val LAST_ISOLATED_UID = 99_999
        private const val SYSTEM_DNS_UID = 1_051
        private const val NETWORK_STACK_UID = 1_073

        private val SYSTEM_UIDS = linkedMapOf(
            "system_dns" to SYSTEM_DNS_UID,
            "network_stack" to NETWORK_STACK_UID,
        )

        fun build(
            androidUserIds: Set<Int>,
            excludedUids: Set<Int>,
            appUid: Int,
        ): AndroidUidPolicy {
            val userIds = androidUserIds.ifEmpty { setOf(0) }.distinct().sorted()
            userIds.forEach { require(it in 0..999) { "Invalid Android user ID: $it" } }

            val includeRanges = buildList {
                userIds.forEach { userId ->
                    val base = userId * PER_USER_RANGE
                    add("${base + FIRST_APPLICATION_UID}:${base + LAST_SDK_SANDBOX_UID}")
                    add("${base + FIRST_ISOLATED_UID}:${base + LAST_ISOLATED_UID}")
                }
            }

            // Isolated/App Zygote UIDs have no reliable static parent mapping. They must
            // remain captured even if stale or manually supplied exclusion data exists.
            val effectiveExclusions = effectiveExclusionUids(excludedUids + appUid).toList()
            val directExclusions = (excludedUids + appUid)
                .asSequence()
                .filter { it >= 0 && !isIsolatedUid(it) }
                .toSortedSet()
            val derivedSandboxExclusions = effectiveExclusions
                .asSequence()
                .filterNot(directExclusions::contains)
                .toSortedSet()

            return AndroidUidPolicy(
                includeUids = SYSTEM_UIDS.values.sorted(),
                includeUidRanges = includeRanges,
                excludeUids = effectiveExclusions,
                summary = UidPolicySummary(
                    systemUids = SYSTEM_UIDS.toMap(),
                    includeUidRanges = includeRanges,
                    androidUserCount = userIds.size,
                    effectiveExcludedUidCount = effectiveExclusions.size,
                    sdkSandboxDerivedExclusionCount = derivedSandboxExclusions.size,
                ),
            )
        }

        /**
         * The cgroup policy has no trustworthy parent relationship for isolated
         * UIDs. Only a normal app UID and its deterministic SDK Sandbox UID are
         * safe to treat as an application's direct-connection identity.
         */
        fun effectiveExclusionUids(uids: Set<Int>): Set<Int> {
            val direct = uids.asSequence()
                .filter { it >= 0 && !isIsolatedUid(it) }
                .toSortedSet()
            return (direct + direct.mapNotNull(::sdkSandboxUidForApplication)).toSortedSet()
        }

        private fun sdkSandboxUidForApplication(uid: Int): Int? {
            val relativeUid = uid % PER_USER_RANGE
            return if (relativeUid in FIRST_APPLICATION_UID..LAST_APPLICATION_UID) {
                uid + FIRST_APPLICATION_UID
            } else {
                null
            }
        }

        fun isIsolatedUid(uid: Int): Boolean =
            uid % PER_USER_RANGE in FIRST_ISOLATED_UID..LAST_ISOLATED_UID
    }
}
