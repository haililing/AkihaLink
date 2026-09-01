package com.akiha.akihalink.root

import com.topjohnwu.superuser.Shell
import com.akiha.akihalink.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandResult(
    val code: Int,
    val output: String,
    val error: String = "",
) {
    fun failureMessage(): String = error.ifBlank { output }
}

interface RootCommandExecutor {
    suspend fun hasRoot(): Boolean
    suspend fun execute(vararg command: String): CommandResult
}

class LibsuRootCommandExecutor : RootCommandExecutor {
    override suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    override suspend fun execute(vararg command: String): CommandResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(*command).exec()
        CommandResult(
            code = result.code,
            output = result.out.joinToString("\n"),
            error = result.err.joinToString("\n"),
        )
    }
}

internal object RootCommandExecutorFactory {
    fun create(): RootCommandExecutor =
        if (BuildConfig.BENCHMARK_MODE) {
            BenchmarkRootCommandExecutor
        } else {
            LibsuRootCommandExecutor()
        }
}

/**
 * Deterministic, network-free backend compiled out of minified release builds.
 * It exists only so Macrobenchmark can measure the App-side connect journey.
 */
internal object BenchmarkRootCommandExecutor : RootCommandExecutor {
    private val running = AtomicBoolean(false)

    override suspend fun hasRoot(): Boolean = true

    override suspend fun execute(vararg command: String): CommandResult {
        val value = command.joinToString(" ")
        return when {
            value.endsWith(" version") -> CommandResult(
                0,
                """{"moduleVersion":"${BuildConfig.VERSION_NAME}","protocolVersion":${BuildConfig.CONTROL_PROTOCOL_VERSION},"coreCommit":"${BuildConfig.CORE_COMMIT}","corePatchSet":"${BuildConfig.CORE_PATCH_SET}","edition":"generic","featureSet":[${FEATURES.joinToString { "\"$it\"" }}]}""",
            )
            value.endsWith(" probe") -> CommandResult(
                0,
                """{"ok":true,"kernel":"benchmark","cgroup2":true,"kernelCapabilities":{"availableTcpCongestionControls":["bbr"],"tcpFastOpenClient":"enabled"}}""",
            )
            value.endsWith(" probe-hotspot") -> CommandResult(0, "{\"supported\":true}")
            value.endsWith(" status-fast") || value.endsWith(" status") || value.endsWith(" status-health") -> status()
            value.endsWith(" uid-policy-status") -> CommandResult(
                0,
                "{\"generation\":1,\"configuredFingerprint\":\"benchmark\",\"liveFingerprint\":\"benchmark\",\"effectiveUids\":0,\"tcpBypassHits\":0,\"udpBypassHits\":0,\"inSync\":true,\"known\":true}",
            )
            value.contains(" apply-exclusions ") -> CommandResult(
                0,
                "{\"runningStatus\":${status().output},\"policyStatus\":{\"generation\":1,\"configuredFingerprint\":\"benchmark\",\"liveFingerprint\":\"benchmark\",\"effectiveUids\":0,\"tcpBypassHits\":0,\"udpBypassHits\":0,\"inSync\":true,\"known\":true},\"connectionReset\":{}}",
            )
            value.contains(" activate ") -> {
                running.set(true)
                CommandResult(
                    0,
                    "{\"status\":${status().output},\"outcome\":\"started\",\"failureStage\":null,\"rolledBack\":false}",
                )
            }
            value.contains(" apply ") -> CommandResult(0, "")
            value.matches(Regex(".*\\sstart(?:\\s[0-9a-f]{16})?$")) -> {
                running.set(true)
                status()
            }
            value.matches(Regex(".*\\sstop(?:\\s.*)?$")) -> {
                running.set(false)
                status()
            }
            value.matches(Regex(".*\\srestart(?:\\s[0-9a-f]{16})?$")) -> {
                running.set(true)
                status()
            }
            else -> CommandResult(0, "{}")
        }
    }

    private fun status(): CommandResult = CommandResult(
        0,
        """{"protocolVersion":${BuildConfig.CONTROL_PROTOCOL_VERSION},"desiredState":"${if (running.get()) "running" else "stopped"}","actualState":"${if (running.get()) "running" else "stopped"}","mode":"rule","coreVersion":"benchmark","ebpfAttached":${running.get()}}""",
    )

    private val FEATURES = setOf(
        "ebpf_core_relocation", "upstream_cgroup_ebpf", "flow_telemetry", "rtnetlink_observer",
        "passive_path_observation",
        "pidfd_supervision", "perfetto_trace_markers", "live_uid_policy_status", "uid_policy_connection_reset",
        "atomic_config_activation", "authenticated_startup_gate",
        "udp_oob_batch", "minimal_core_registry", "on_demand_tcp_info", "wifi_hotspot_proxy",
        "cilium_ebpf_backend", "ebpf_kernel_probe",
    )
}
