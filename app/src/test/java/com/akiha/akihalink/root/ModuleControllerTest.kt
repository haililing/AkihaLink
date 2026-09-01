package com.akiha.akihalink.root

import com.akiha.akihalink.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModuleControllerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun reportsRootDeniedWithoutCallingController() = runBlocking {
        val executor = FakeExecutor(root = false)
        val compatibility = ModuleController(temporaryFolder.root, executor).compatibility()
        assertEquals(ModuleCompatibility.RootDenied, compatibility)
        assertTrue(executor.commands.isEmpty())
    }

    @Test
    fun reportsMissingAndMismatchedModule() = runBlocking {
        val missing = FakeExecutor(responses = mutableListOf(CommandResult(127, "not found")))
        assertEquals(ModuleCompatibility.Missing, ModuleController(temporaryFolder.root, missing).compatibility())

        val mismatch = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, "{\"moduleVersion\":\"0.1.0\",\"protocolVersion\":9,\"coreCommit\":\"x\"}"),
            ),
        )
        assertEquals(
            ModuleCompatibility.VersionMismatch(9),
            ModuleController(temporaryFolder.root, mismatch).compatibility(),
        )
    }

    @Test
    fun rejectsOldModuleByVersionOrProtocol() = runBlocking {
        val oldVersion = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, versionJson(moduleVersion = "0.14.0")),
            ),
        )
        assertEquals(
            ModuleCompatibility.VersionMismatch(BuildConfig.CONTROL_PROTOCOL_VERSION),
            ModuleController(temporaryFolder.root, oldVersion).compatibility(),
        )

        val oldProtocol = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, versionJson(protocolVersion = 13)),
            ),
        )
        assertEquals(
            ModuleCompatibility.VersionMismatch(13),
            ModuleController(temporaryFolder.root, oldProtocol).compatibility(),
        )
    }

    @Test
    fun acceptsOnlyPinnedCoreAndPatchSet() = runBlocking {
        val compatible = FakeExecutor(
            responses = mutableListOf(
                CommandResult(
                    0,
                    versionJson(),
                ),
            ),
        )
        assertEquals(
            ModuleCompatibility.Compatible,
            ModuleController(temporaryFolder.root, compatible).compatibility(),
        )

        val wrongPatch = FakeExecutor(
            responses = mutableListOf(
                CommandResult(
                    0,
                    versionJson(patchSet = "unexpected"),
                ),
            ),
        )
        assertEquals(
            ModuleCompatibility.CoreMismatch,
            ModuleController(temporaryFolder.root, wrongPatch).compatibility(),
        )
    }

    @Test
    fun rejectsMismatchedEdition() = runBlocking {
        val otherEdition = "unsupported"
        val executor = FakeExecutor(
            responses = mutableListOf(CommandResult(0, versionJson(edition = otherEdition))),
        )

        assertEquals(
            ModuleCompatibility.EditionMismatch(otherEdition),
            ModuleController(temporaryFolder.root, executor).compatibility(),
        )
    }

    @Test
    fun rejectsOldFeatureSet() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, versionJson(features = listOf("core", "ordered_failover"))),
            ),
        )

        assertEquals(
            ModuleCompatibility.FeatureMismatch(
                listOf(
                    "atomic_config_activation",
                    "authenticated_startup_gate",
                    "ebpf_core_relocation",
                    "flow_telemetry",
                    "live_uid_policy_status",
                    "minimal_core_registry",
                    "on_demand_tcp_info",
                    "passive_path_observation",
                    "perfetto_trace_markers",
                    "pidfd_supervision",
                    "rtnetlink_observer",
                    "udp_oob_batch",
                    "uid_policy_connection_reset",
                    "upstream_cgroup_ebpf",
                    "wifi_hotspot_proxy",
                ),
            ),
            ModuleController(temporaryFolder.root, executor).compatibility(),
        )
    }

    @Test
    fun decodesKernelCapabilitiesFromProbe() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(
                    0,
                    "{\"ok\":true,\"kernel\":\"6.6\",\"cgroup2\":true," +
                        "\"verifierError\":null,\"kernelCapabilities\":{" +
                        "\"configSource\":\"proc_config_gz\",\"bpfJit\":\"enabled\"," +
                        "\"btf\":\"enabled\",\"cgroupBpf\":\"enabled\",\"fq\":\"enabled\"," +
                        "\"hmbird\":\"enabled\",\"rekernelNetwork\":\"enabled\"," +
                        "\"tcpBrutal\":\"disabled\",\"tcpFastOpenClient\":\"enabled\"," +
                        "\"btfRootReadable\":true,\"coreRelocation\":true,\"bpffs\":true," +
                        "\"bpfLink\":true,\"bpfLinkUpdate\":true,\"ringBuffer\":true," +
                        "\"sockOps\":true,\"tcpInfo\":true,\"rtnetlink\":true," +
                        "\"tcpMtuProbing\":1,\"perSocketPmtu\":true,\"perSocketMss\":true," +
                        "\"quicDplpmtud\":true,\"pidfdOpen\":true,\"pidfdSendSignal\":true," +
                        "\"pidfdPoll\":true," +
                        "\"availableTcpCongestionControls\":[\"cubic\",\"bbr\"]," +
                        "\"currentTcpCongestionControl\":\"cubic\",\"tcpEcn\":2,\"defaultQdisc\":\"fq\"}}",
                ),
            ),
        )

        val probe = ModuleController(temporaryFolder.root, executor).probe()

        assertTrue(probe.ok)
        assertEquals(listOf("cubic", "bbr"), probe.kernelCapabilities.availableTcpCongestionControls)
        assertEquals("enabled", probe.kernelCapabilities.hmbird)
        assertEquals("disabled", probe.kernelCapabilities.tcpBrutal)
        assertEquals("enabled", probe.kernelCapabilities.tcpFastOpenClient)
        assertTrue(probe.kernelCapabilities.coreRelocation == true)
        assertTrue(probe.kernelCapabilities.ringBuffer == true)
        assertEquals(1, probe.kernelCapabilities.tcpMtuProbing)
        assertTrue(probe.kernelCapabilities.pidfdOpen == true)
        assertEquals(
            "timeout 20s /data/adb/modules/akihalink/bin/akihalinkctl probe",
            executor.commands.single(),
        )
    }

    @Test
    fun decodesHotspotCapabilityProbe() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(CommandResult(0, "{\"supported\":true}")),
        )

        assertTrue(ModuleController(temporaryFolder.root, executor).probeHotspot().supported)
        assertEquals(
            "timeout 20s /data/adb/modules/akihalink/bin/akihalinkctl probe-hotspot",
            executor.commands.single(),
        )
    }

    @Test
    fun preservesStructuredProbeFailure() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(
                    1,
                    "{\"ok\":false,\"kernel\":\"6.6\",\"cgroup2\":true," +
                        "\"verifierError\":\"attach rejected\",\"kernelCapabilities\":{" +
                        "\"availableTcpCongestionControls\":[\"cubic\"]}}",
                ),
            ),
        )

        val probe = ModuleController(temporaryFolder.root, executor).probe()

        assertFalse(probe.ok)
        assertEquals("attach rejected", probe.verifierError)
        assertEquals(listOf("cubic"), probe.kernelCapabilities.availableTcpCongestionControls)
    }

    @Test
    fun appliesThroughPrivateTemporaryFileAndRemovesIt() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, ""),
                CommandResult(0, STATUS_JSON),
            ),
        )
        val status = ModuleController(temporaryFolder.root, executor).apply("{\"inbounds\":[]}")

        assertEquals("stopped", status.actualState)
        assertTrue(executor.commands.first().contains(" apply "))
        assertTrue(executor.commands.last().endsWith("akihalinkctl status-health"))
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.name.startsWith("akihalink-") })
    }

    @Test
    fun activatesThroughAtomicCommandAndCleansStagedConfig() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, "{\"status\":$STATUS_JSON,\"outcome\":\"started\",\"failureStage\":null,\"rolledBack\":false}"),
            ),
        )

        val result = ModuleController(temporaryFolder.root, executor)
            .activate("{\"inbounds\":[]}", "0123456789abcdef")

        assertEquals("started", result.outcome)
        assertTrue(executor.commands.single().contains("timeout 20s"))
        assertTrue(executor.commands.single().contains(" activate "))
        assertTrue(executor.commands.single().endsWith(" 0123456789abcdef"))
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any {
            it.name.startsWith("akihalink-activate-")
        })
    }

    @Test
    fun preservesStructuredActivationFailure() = runBlocking {
        val payload = "{\"status\":$STATUS_JSON,\"outcome\":\"rolled_back\",\"failureStage\":\"startup_readiness\",\"rolledBack\":true}"
        val executor = FakeExecutor(responses = mutableListOf(CommandResult(3, payload)))

        val error = runCatching {
            ModuleController(temporaryFolder.root, executor).activate("{}")
        }.exceptionOrNull()

        assertTrue(error is ActivationException)
        assertEquals("startup_readiness", (error as ActivationException).activation.failureStage)
        assertTrue(error.activation.rolledBack)
    }

    @Test
    fun appliesExclusionsAsVerifiedPolicyTransaction() = runBlocking {
        val result = """
            {"runningStatus":$STATUS_JSON,
             "policyStatus":{"generation":4,"configuredFingerprint":"a","liveFingerprint":"a","effectiveUids":2,"tcpBypassHits":8,"udpBypassHits":3,"inSync":true,"known":true},
             "connectionReset":{"requestedPackages":2,"stoppedPackages":2,"verifiedUids":3,"remainingUids":[]}}
        """.trimIndent()
        val executor = FakeExecutor(responses = mutableListOf(CommandResult(0, result)))
        val controller = ModuleController(
            temporaryFolder.root,
            executor,
            UidPackageResolver { listOf("com.example.video", "com.example.video.shared") },
        )

        val applied = controller.applyExclusions("{\"inbounds\":[]}", setOf(10_123))

        assertEquals("stopped", applied.runningStatus.actualState)
        assertTrue(applied.policyStatus.inSync)
        assertEquals(2, applied.connectionReset.stoppedPackages)
        assertTrue(executor.commands.single().contains("timeout 16s"))
        assertTrue(executor.commands.single().contains(" apply-exclusions "))
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any {
            it.name.startsWith("akihalink-uid-reset-")
        })
    }

    @Test
    fun queriesFastStatusWithoutUsingFullStatus() = runBlocking {
        val executor = FakeExecutor(responses = mutableListOf(CommandResult(0, STATUS_JSON)))

        val status = ModuleController(temporaryFolder.root, executor).statusFast()

        assertEquals("stopped", status.actualState)
        assertEquals(
            "timeout 8s /data/adb/modules/akihalink/bin/akihalinkctl status-fast",
            executor.commands.single(),
        )
    }

    @Test
    fun decodesHotspotRuntimeState() = runBlocking {
        val payload = STATUS_JSON.replace(
            "\"ebpfAttached\":false",
            "\"ebpfAttached\":true,\"hotspotProxyState\":\"attached\"",
        )
        val status = ModuleController(
            temporaryFolder.root,
            FakeExecutor(responses = mutableListOf(CommandResult(0, payload))),
        ).statusFast()

        assertEquals("attached", status.hotspotProxyState)
    }

    @Test
    fun queriesBoundedControllerHealthStatus() = runBlocking {
        val executor = FakeExecutor(responses = mutableListOf(CommandResult(0, STATUS_JSON)))

        val status = ModuleController(temporaryFolder.root, executor).statusHealth()

        assertEquals("stopped", status.actualState)
        assertEquals(
            "timeout 8s /data/adb/modules/akihalink/bin/akihalinkctl status-health",
            executor.commands.single(),
        )
    }

    @Test
    fun decodesEscapedCoreConfigErrorFromFastStatus() = runBlocking {
        val coreError = "json: unknown field \"include_android_system_resolver\""
        val payload = STATUS_JSON.replace(
            "\"lastError\":null",
            "\"lastError\":\"json: unknown field \\\"include_android_system_resolver\\\"\"",
        )
        val executor = FakeExecutor(responses = mutableListOf(CommandResult(0, payload)))

        val status = ModuleController(temporaryFolder.root, executor).statusFast()

        assertEquals(coreError, status.lastError)
    }

    @Test
    fun forwardsOptionalTraceId() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(0, ""),
                CommandResult(0, STATUS_JSON),
            ),
        )
        val controller = ModuleController(temporaryFolder.root, executor)

        controller.apply("{\"inbounds\":[]}", "0123456789abcdef")

        assertTrue(executor.commands.first().endsWith(" 0123456789abcdef"))
    }

    @Test
    fun rejectsUnsafeTraceIdBeforeExecutingRootCommand() = runBlocking {
        val executor = FakeExecutor()
        val controller = ModuleController(temporaryFolder.root, executor)

        runCatching { controller.apply("{}", "node.example:443") }

        assertTrue(executor.commands.isEmpty())
    }

    @Test
    fun reportsStructuredStopFailureInsteadOfRawStatusJson() = runBlocking {
        val failure = STATUS_JSON
            .replace("\"actualState\":\"stopped\"", "\"actualState\":\"failed\"")
            .replace("\"lastError\":null", "\"lastError\":\"sing-box exited with code 2\"")
        val executor = FakeExecutor(
            responses = mutableListOf(CommandResult(1, failure)),
        )

        val error = runCatching {
            ModuleController(temporaryFolder.root, executor).stop()
        }.exceptionOrNull()

        assertEquals("sing-box exited with code 2", error?.message)
        assertFalse(error?.message.orEmpty().startsWith("{"))
    }

    @Test
    fun startsAndStopsAuxiliarySpeedTestThroughPrivateConfig() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(
                    0,
                    "{\"running\":true,\"port\":19090,\"pid\":123,\"expiresAt\":999,\"readiness\":\"ready\",\"lastError\":null}",
                ),
                CommandResult(
                    0,
                    "{\"running\":false,\"port\":19090,\"pid\":null,\"expiresAt\":null,\"lastError\":null}",
                ),
            ),
        )
        val controller = ModuleController(temporaryFolder.root, executor)

        val started = controller.startSpeedTest("{\"outbounds\":[]}")
        val stopped = controller.stopSpeedTest()

        assertTrue(started.running)
        assertEquals(19_090, started.port)
        assertEquals(SpeedTestReadiness.READY, started.readiness)
        assertFalse(stopped.running)
        assertTrue(executor.commands.first().contains("timeout 15s "))
        assertTrue(executor.commands.first().contains(" speedtest-start "))
        assertEquals("timeout 15s /data/adb/modules/akihalink/bin/akihalinkctl speedtest-stop", executor.commands.last())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.name.startsWith("akihalink-speedtest-") })
    }

    @Test
    fun reportsStructuredSpeedTestFailureInsteadOfRawStatusJson() = runBlocking {
        val executor = FakeExecutor(
            responses = mutableListOf(
                CommandResult(
                    1,
                    "{\"running\":false,\"port\":19090,\"pid\":null," +
                        "\"expiresAt\":null,\"lastError\":null}",
                ),
            ),
        )

        val error = runCatching {
            ModuleController(temporaryFolder.root, executor).startSpeedTest("{\"outbounds\":[]}")
        }.exceptionOrNull()

        assertEquals("测速核心启动失败，请查看模块日志", error?.message)
        assertFalse(error?.message.orEmpty().startsWith("{"))
    }

    private class FakeExecutor(
        private val root: Boolean = true,
        private val responses: MutableList<CommandResult> = mutableListOf(),
    ) : RootCommandExecutor {
        val commands = mutableListOf<String>()

        override suspend fun hasRoot(): Boolean = root

        override suspend fun execute(vararg command: String): CommandResult {
            commands += command.joinToString(" ")
            return responses.removeFirstOrNull() ?: CommandResult(0, "")
        }
    }

    private companion object {
        val STATUS_JSON = "{\"protocolVersion\":${BuildConfig.CONTROL_PROTOCOL_VERSION}," +
            "\"desiredState\":\"stopped\",\"actualState\":\"stopped\",\"mode\":\"rule\",\"pid\":null," +
            "\"startedAt\":null,\"coreVersion\":\"1.14\",\"ebpfAttached\":false,\"lastError\":null}"

        fun versionJson(
            moduleVersion: String = BuildConfig.VERSION_NAME,
            protocolVersion: Int = BuildConfig.CONTROL_PROTOCOL_VERSION,
            edition: String = BuildConfig.EDITION,
            patchSet: String = BuildConfig.CORE_PATCH_SET,
            features: List<String> = listOf(
                "core",
                "ebpf_core_relocation",
                "upstream_cgroup_ebpf",
                "flow_telemetry",
                "live_uid_policy_status",
                "rtnetlink_observer",
                "passive_path_observation",
                "pidfd_supervision",
                "perfetto_trace_markers",
                "atomic_config_activation",
                "authenticated_startup_gate",
                "udp_oob_batch",
                "uid_policy_connection_reset",
                "minimal_core_registry",
                "on_demand_tcp_info",
                "wifi_hotspot_proxy",
            ),
        ): String = "{\"moduleVersion\":\"$moduleVersion\"," +
            "\"protocolVersion\":$protocolVersion," +
            "\"coreCommit\":\"${BuildConfig.CORE_COMMIT}\"," +
            "\"corePatchSet\":\"$patchSet\",\"edition\":\"$edition\"," +
            "\"featureSet\":[${features.joinToString { "\"$it\"" }}]}"
    }
}
