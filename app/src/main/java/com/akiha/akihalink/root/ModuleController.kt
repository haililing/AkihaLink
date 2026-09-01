package com.akiha.akihalink.root

import android.content.Context
import com.akiha.akihalink.BuildConfig
import com.akiha.akihalink.config.AndroidUidPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ModuleVersion(
    val moduleVersion: String = "",
    val protocolVersion: Int = 0,
    val coreCommit: String = "",
    val corePatchSet: String = "",
    val edition: String = "generic",
    val featureSet: List<String> = emptyList(),
)

@Serializable
data class KernelCapabilities(
    val configSource: String = "runtime_only",
    val bpfJit: String = "unknown",
    val btf: String = "unknown",
    val cgroupBpf: String = "unknown",
    val fq: String = "unknown",
    val hmbird: String = "unknown",
    val rekernelNetwork: String = "unknown",
    val tcpBrutal: String = "unknown",
    val tcpFastOpenClient: String = "unknown",
    val availableTcpCongestionControls: List<String> = emptyList(),
    val currentTcpCongestionControl: String? = null,
    val tcpEcn: Int? = null,
    val defaultQdisc: String? = null,
    val btfRootReadable: Boolean? = null,
    val coreRelocation: Boolean? = null,
    val bpffs: Boolean? = null,
    val bpfLink: Boolean? = null,
    val bpfLinkUpdate: Boolean? = null,
    val ringBuffer: Boolean? = null,
    val sockOps: Boolean? = null,
    val tcpInfo: Boolean? = null,
    val rtnetlink: Boolean? = null,
    val tcpMtuProbing: Int? = null,
    val perSocketPmtu: Boolean? = null,
    val perSocketMss: Boolean? = null,
    val quicDplpmtud: Boolean? = null,
    val pidfdOpen: Boolean? = null,
    val pidfdSendSignal: Boolean? = null,
    val pidfdPoll: Boolean? = null,
)

@Serializable
data class ModuleStatus(
    val protocolVersion: Int = 0,
    val desiredState: String = "stopped",
    val actualState: String = "unknown",
    val mode: String = "rule",
    val pid: Int? = null,
    val startedAt: Long? = null,
    val coreVersion: String = "",
    val ebpfAttached: Boolean = false,
    val hotspotProxyState: String = "disabled",
    val controllerReady: Boolean? = null,
    val startupHealth: String = "unknown",
    val systemResolverAttached: Boolean = false,
    val androidDnsCacheFlush: String = "not_run",
    val systemResolverDiscovered: Int = 0,
    val dnsPlainCaptureCount: Long = 0,
    val dnsOverTlsCaptureCount: Long = 0,
    val dnsOverHttpsCaptureCount: Long = 0,
    val resolverProbeBypassCount: Long = 0,
    val resolverMonitorMode: String = "unavailable",
    val resolverRediscoveries: Long = 0,
    val tcpActiveSamples: Long = 0,
    val tcpIdleSamples: Long = 0,
    val tcpSamplingMode: String = "on_demand",
    val tcpSamplingLeaseActive: Boolean = false,
    val tcpSamplingLeaseExpiresAt: Long = 0,
    val tcpSampleGeneration: Long = 0,
    val daemonState: String = "stopped",
    val loaderMode: String = "unavailable",
    val attachMode: String = "none",
    val eventMode: String = "none",
    val pinGeneration: Long = 0,
    val processSupervisorMode: String = "unavailable",
    val routeGeneration: Long = 0,
    val mtuMode: String = "unavailable",
    val mtuDegradedReason: String? = null,
    val lastError: String? = null,
)

@Serializable
data class ActivationResult(
    val status: ModuleStatus = ModuleStatus(),
    val outcome: String = "failed_clean",
    val failureStage: String? = null,
    val rolledBack: Boolean = false,
)

class ActivationException(
    val activation: ActivationResult,
    message: String,
) : IllegalStateException(message)

@Serializable
enum class SpeedTestReadiness {
    @SerialName("ready") READY,
    @SerialName("unverified") UNVERIFIED,
    @SerialName("failed") FAILED,
}

@Serializable
data class ProbeResult(
    val ok: Boolean = false,
    val kernel: String = "",
    val cgroup2: Boolean = false,
    val verifierError: String? = null,
    val kernelCapabilities: KernelCapabilities = KernelCapabilities(),
)

@Serializable
data class HotspotProbeResult(
    val supported: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SpeedTestStatus(
    val running: Boolean = false,
    val port: Int = 19_090,
    val pid: Int? = null,
    val expiresAt: Long? = null,
    val readiness: SpeedTestReadiness? = null,
    val lastError: String? = null,
)

@Serializable
data class UidPolicyStatus(
    val generation: Long = 0,
    val configuredFingerprint: String = "",
    val liveFingerprint: String = "",
    val effectiveUids: Int = 0,
    val tcpBypassHits: Long = 0,
    val udpBypassHits: Long = 0,
    val inSync: Boolean = false,
    val known: Boolean = false,
)

@Serializable
data class UidPolicyConnectionReset(
    val requestedPackages: Int = 0,
    val stoppedPackages: Int = 0,
    val verifiedUids: Int = 0,
    val remainingUids: List<Int> = emptyList(),
)

@Serializable
data class UidPolicyApplyResult(
    val runningStatus: ModuleStatus = ModuleStatus(),
    val policyStatus: UidPolicyStatus = UidPolicyStatus(),
    val connectionReset: UidPolicyConnectionReset = UidPolicyConnectionReset(),
)

@Serializable
internal data class UidPolicyResetTarget(
    val uid: Int,
    val userId: Int,
    val packageName: String,
)

@Serializable
internal data class UidPolicyResetRequest(
    val packages: List<UidPolicyResetTarget>,
    val verifyUids: List<Int>,
)

fun interface UidPackageResolver {
    fun packagesForUid(uid: Int): List<String>
}

private class AndroidUidPackageResolver(context: Context) : UidPackageResolver {
    private val packageManager = context.applicationContext.packageManager

    override fun packagesForUid(uid: Int): List<String> =
        packageManager.getPackagesForUid(uid)?.toList().orEmpty()
}

sealed interface ModuleCompatibility {
    data object Compatible : ModuleCompatibility
    data object RootDenied : ModuleCompatibility
    data object Missing : ModuleCompatibility
    data class VersionMismatch(val installed: Int) : ModuleCompatibility
    data class EditionMismatch(val installed: String) : ModuleCompatibility
    data object CoreMismatch : ModuleCompatibility
    data class FeatureMismatch(val missing: List<String>) : ModuleCompatibility
    data class Error(val message: String) : ModuleCompatibility
}

class ModuleController(
    private val stagingDirectory: File,
    private val executor: RootCommandExecutor = RootCommandExecutorFactory.create(),
    private val uidPackageResolver: UidPackageResolver = UidPackageResolver { emptyList() },
) {
    constructor(
        context: Context,
        executor: RootCommandExecutor = RootCommandExecutorFactory.create(),
    ) : this(context.cacheDir, executor, AndroidUidPackageResolver(context))

    suspend fun compatibility(): ModuleCompatibility {
        if (!executor.hasRoot()) return ModuleCompatibility.RootDenied
        val result = executeBounded("version", ENVIRONMENT_TIMEOUT_SECONDS)
        if (result.code != 0) return if (
            result.code == 127 || result.failureMessage().contains("not found", ignoreCase = true) ||
            result.failureMessage().contains("No such file", ignoreCase = true)
        ) ModuleCompatibility.Missing
            else ModuleCompatibility.Error(result.failureMessage().take(300))
        val version = runCatching { JSON.decodeFromString<ModuleVersion>(result.output) }
            .getOrElse { return ModuleCompatibility.Error("模块返回了无效的版本信息") }
        if (version.protocolVersion != BuildConfig.CONTROL_PROTOCOL_VERSION ||
            version.moduleVersion != BuildConfig.VERSION_NAME
        ) return ModuleCompatibility.VersionMismatch(version.protocolVersion)
        if (version.edition != BuildConfig.EDITION) return ModuleCompatibility.EditionMismatch(version.edition)
        if (version.coreCommit != BuildConfig.CORE_COMMIT ||
            version.corePatchSet != BuildConfig.CORE_PATCH_SET
        ) return ModuleCompatibility.CoreMismatch
        val missing = REQUIRED_FEATURES - version.featureSet.toSet()
        if (missing.isNotEmpty()) return ModuleCompatibility.FeatureMismatch(missing.sorted())
        return ModuleCompatibility.Compatible
    }

    suspend fun statusFast(): ModuleStatus = decode(
        executeBounded("status-fast", ENVIRONMENT_TIMEOUT_SECONDS),
    )
    suspend fun statusHealth(): ModuleStatus = decode(
        executeBounded("status-health", ENVIRONMENT_TIMEOUT_SECONDS),
    )
    suspend fun probe(): ProbeResult {
        val result = executeBounded("probe", PROBE_TIMEOUT_SECONDS)
        runCatching { JSON.decodeFromString<ProbeResult>(result.output) }.getOrNull()?.let { return it }
        if (result.code != 0) error(result.failureMessage().ifBlank { "eBPF probe 失败" }.take(500))
        error("模块返回了无效的 probe 信息")
    }
    suspend fun probeHotspot(): HotspotProbeResult {
        val result = executeBounded("probe-hotspot", PROBE_TIMEOUT_SECONDS)
        runCatching { JSON.decodeFromString<HotspotProbeResult>(result.output) }.getOrNull()?.let { return it }
        if (result.code != 0) error(result.failureMessage().ifBlank { "热点代理能力探测失败" }.take(500))
        error("模块返回了无效的热点代理探测信息")
    }
    suspend fun activate(config: String, traceId: String? = null): ActivationResult {
        validateTraceId(traceId)
        val staged = createStagedConfig("akihalink-activate-", config)
        return try {
            val suffix = traceId?.let { " $it" }.orEmpty()
            val result = executeBounded(
                "activate ${shellQuote(staged.absolutePath)}$suffix",
                ACTIVATION_TIMEOUT_SECONDS,
            )
            val activation = runCatching {
                JSON.decodeFromString<ActivationResult>(result.output)
            }.getOrNull()
            if (result.code != 0) {
                if (activation != null) {
                    throw ActivationException(
                        activation,
                        activation.status.lastError
                            ?: "Activation failed during ${activation.failureStage ?: "unknown"}",
                    )
                }
                error(result.failureMessage().ifBlank { "Activation failed" }.take(500))
            }
            activation ?: error("Module returned an invalid activation result")
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }
    }
    suspend fun stop(mode: String? = null): ModuleStatus {
        require(mode == null || mode in setOf("global", "rule", "direct")) { "Invalid proxy mode" }
        return commandWithStatus(if (mode == null) "stop" else "stop $mode")
    }
    suspend fun stopSpeedTest(): SpeedTestStatus = decode(
        executeBounded("speedtest-stop", SPEED_TEST_CONTROL_TIMEOUT_SECONDS),
    )
    suspend fun updateTelemetryTarget(nodeTag: String, nodeFingerprint: String) {
        require(nodeTag.matches(SAFE_TAG)) { "Invalid telemetry target tag" }
        require(nodeFingerprint.matches(HEX_FINGERPRINT)) { "Invalid node fingerprint" }
        val result = executeBounded(
            "observability-target ${shellQuote(nodeTag)} ${shellQuote(nodeFingerprint)}",
            OBSERVABILITY_TIMEOUT_SECONDS,
        )
        if (result.code != 0) error(result.failureMessage().ifBlank { "Telemetry target update failed" }.take(300))
    }
    suspend fun startSpeedTest(config: String): SpeedTestStatus {
        val staged = createStagedConfig("akihalink-speedtest-", config)
        return try {
            val result = executeBounded(
                "speedtest-start ${shellQuote(staged.absolutePath)}",
                SPEED_TEST_CONTROL_TIMEOUT_SECONDS,
            )
            val returnedStatus = runCatching {
                JSON.decodeFromString<SpeedTestStatus>(result.output)
            }.getOrNull()
            if (result.code != 0) {
                error(
                    returnedStatus?.lastError?.takeIf(String::isNotBlank)
                        ?: result.failureMessage().trim().takeUnless { it.startsWith("{") }?.take(500)
                        ?: "测速核心启动失败，请查看模块日志",
                )
            }
            returnedStatus ?: error("测速模块返回了无效状态")
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }
    }

    suspend fun apply(config: String, traceId: String? = null): ModuleStatus {
        validateTraceId(traceId)
        val staged = createStagedConfig("akihalink-", config)
        return try {
            val suffix = traceId?.let { " $it" }.orEmpty()
            val result = executeBounded(
                "apply ${shellQuote(staged.absolutePath)}$suffix",
                ACTIVATION_TIMEOUT_SECONDS,
            )
            if (result.code != 0) error(result.failureMessage().ifBlank { "配置应用失败" }.take(500))
            statusHealth()
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }
    }

    suspend fun applyExclusions(
        config: String,
        affectedUids: Set<Int>,
    ): UidPolicyApplyResult {
        val staged = createStagedConfig("akihalink-exclusions-", config)
        val reset = createStagedUidPolicyResetRequest(affectedUids)
        return try {
            val result = executeBounded(
                "apply-exclusions ${shellQuote(staged.absolutePath)} ${shellQuote(reset.absolutePath)}",
                UID_POLICY_APPLY_TIMEOUT_SECONDS,
            )
            if (result.code != 0) error(result.failureMessage().ifBlank { "应用排除应用失败" }.take(500))
            JSON.decodeFromString<UidPolicyApplyResult>(result.output)
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
            withContext(Dispatchers.IO) { reset.delete() }
        }
    }

    private suspend fun executeBounded(command: String, timeoutSeconds: Int): CommandResult {
        val result = executor.execute("timeout ${timeoutSeconds}s $CTL $command")
        return if (result.code == COMMAND_TIMEOUT_EXIT_CODE) {
            CommandResult(result.code, "模块命令超时：$command")
        } else {
            result
        }
    }

    private suspend fun commandWithStatus(command: String): ModuleStatus {
        val result = executeBounded(command, RUNTIME_MUTATION_TIMEOUT_SECONDS)
        val returnedStatus = runCatching {
            JSON.decodeFromString<ModuleStatus>(result.output)
        }.getOrNull()
        if (result.code != 0) {
            error(
                returnedStatus?.lastError?.takeIf(String::isNotBlank)
                    ?: result.failureMessage().trim().takeUnless { it.startsWith("{") }?.take(500)
                    ?: "代理核心启动失败，请查看模块日志",
            )
        }
        return returnedStatus ?: statusHealth()
    }

    private fun validateTraceId(traceId: String?) {
        require(traceId == null || TRACE_ID.matches(traceId)) { "Invalid trace id" }
    }

    private suspend fun createStagedConfig(prefix: String, config: String): File =
        withContext(Dispatchers.IO) {
            File.createTempFile(prefix, ".json", stagingDirectory).apply {
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
                setExecutable(false, false)
                writeText(config)
            }
        }

    private suspend fun createStagedUidPolicyResetRequest(affectedUids: Set<Int>): File {
        require(affectedUids.size <= MAX_UID_POLICY_RESET_UIDS) { "Too many affected application UIDs" }
        val verifyUids = AndroidUidPolicy.effectiveExclusionUids(affectedUids).toList()
        val packages = withContext(Dispatchers.IO) {
            affectedUids.asSequence()
                .filter { it >= 10_000 && it % ANDROID_USER_UID_RANGE <= 19_999 }
                .flatMap { uid ->
                    uidPackageResolver.packagesForUid(uid).asSequence().map { packageName ->
                        UidPolicyResetTarget(
                            uid = uid,
                            userId = uid / ANDROID_USER_UID_RANGE,
                            packageName = packageName,
                        )
                    }
                }
                .filter { it.packageName.matches(PACKAGE_NAME) }
                .distinctBy { "${it.userId}:${it.packageName}" }
                .sortedWith(compareBy(UidPolicyResetTarget::userId, UidPolicyResetTarget::packageName))
                .toList()
        }
        return createStagedConfig(
            "akihalink-uid-reset-",
            JSON.encodeToString(UidPolicyResetRequest(packages, verifyUids)),
        )
    }

    private inline fun <reified T> decode(result: CommandResult): T {
        if (result.code != 0) error(result.failureMessage().ifBlank { "模块命令失败" }.take(500))
        return JSON.decodeFromString(result.output)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val CTL = "/data/adb/modules/akihalink/bin/akihalinkctl"
        const val ENVIRONMENT_TIMEOUT_SECONDS = 8
        const val PROBE_TIMEOUT_SECONDS = 20
        const val OBSERVABILITY_TIMEOUT_SECONDS = 5
        const val ACTIVATION_TIMEOUT_SECONDS = 20
        const val RUNTIME_MUTATION_TIMEOUT_SECONDS = 12
        const val SPEED_TEST_CONTROL_TIMEOUT_SECONDS = 15
        const val UID_POLICY_APPLY_TIMEOUT_SECONDS = 16
        const val COMMAND_TIMEOUT_EXIT_CODE = 124
        const val ANDROID_USER_UID_RANGE = 100_000
        const val MAX_UID_POLICY_RESET_UIDS = 1_000
        val JSON = Json { ignoreUnknownKeys = true }
        val SAFE_TAG = Regex("[A-Za-z0-9_-]{1,128}")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val REQUIRED_FEATURES = setOf(
            "ebpf_core_relocation",
            "upstream_cgroup_ebpf",
            "flow_telemetry",
            "rtnetlink_observer",
            "passive_path_observation",
            "pidfd_supervision",
            "perfetto_trace_markers",
            "atomic_config_activation",
            "authenticated_startup_gate",
            "live_uid_policy_status",
            "uid_policy_connection_reset",
            "udp_oob_batch",
            "minimal_core_registry",
            "on_demand_tcp_info",
            "wifi_hotspot_proxy",
        )
        val HEX_FINGERPRINT = Regex("[0-9a-f]{64}")
        val TRACE_ID = Regex("[0-9a-f]{16}")
    }
}
