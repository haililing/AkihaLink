package com.akiha.akihalink

import com.akiha.akihalink.root.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainViewModelStateTest {
    @Test
    fun appliesNodeDnsValidationOnlyForTheCurrentSelectionTicket() {
        val checking = MainUiState(
            selectedNodeId = "node-b",
            dnsValidation = DnsValidationState.CHECKING,
            dnsValidationNodeId = "node-b",
            dnsValidationGeneration = 7,
        )

        val healthy = applyNodeDnsValidation(
            checking,
            nodeId = "node-b",
            generation = 7,
            validation = DnsValidationState.HEALTHY,
        )

        assertEquals(DnsValidationState.HEALTHY, healthy.dnsValidation)
    }

    @Test
    fun ignoresStaleNodeDnsValidationResults() {
        val current = MainUiState(
            selectedNodeId = "node-b",
            dnsValidation = DnsValidationState.CHECKING,
            dnsValidationNodeId = "node-b",
            dnsValidationGeneration = 8,
        )

        assertEquals(
            current,
            applyNodeDnsValidation(current, "node-a", 7, DnsValidationState.DEGRADED),
        )
        assertEquals(
            current,
            applyNodeDnsValidation(current, "node-b", 7, DnsValidationState.DEGRADED),
        )
    }

    @Test
    fun treatsDesiredAndStartingProcessesAsActive() {
        assertTrue(ModuleStatus(desiredState = "running", actualState = "failed").proxyMayBeActive())
        assertTrue(ModuleStatus(desiredState = "stopped", actualState = "starting").proxyMayBeActive())
        assertTrue(ModuleStatus(desiredState = "stopped", actualState = "running").proxyMayBeActive())
        assertFalse(ModuleStatus(desiredState = "stopped", actualState = "stopped").proxyMayBeActive())
    }

    @Test
    fun retainsControllerHealthOnlyForFastRunningStatus() {
        val previous = ModuleStatus(actualState = "running", startedAt = 100, controllerReady = false)

        assertFalse(
            ModuleStatus(actualState = "running", startedAt = 100, controllerReady = null)
                .retainControllerReadiness(previous).controllerReady == true,
        )
        assertEquals(
            false,
            ModuleStatus(actualState = "running", startedAt = 100, controllerReady = null)
                .retainControllerReadiness(previous).controllerReady,
        )
        assertNull(
            ModuleStatus(actualState = "stopped", controllerReady = null)
                .retainControllerReadiness(previous).controllerReady,
        )
        assertNull(
            ModuleStatus(actualState = "running", startedAt = 200, controllerReady = null)
                .retainControllerReadiness(previous).controllerReady,
        )
    }

    @Test
    fun blocksNodeSwitchingWhileTheProxyIsStarting() {
        assertFalse(ModuleStatus(actualState = "starting").nodeSwitchAllowed())
        assertTrue(ModuleStatus(actualState = "running").nodeSwitchAllowed())
        assertTrue(ModuleStatus(actualState = "stopped").nodeSwitchAllowed())
    }

    @Test
    fun allowsOnlyOneRapidSpeedTestJobClaim() {
        val claim = SpeedTestJobClaim()
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val successes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(16)
        try {
            repeat(16) {
                executor.execute {
                    ready.countDown()
                    start.await()
                    if (claim.tryClaim()) successes.incrementAndGet()
                    done.countDown()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, successes.get())
            claim.release()
            assertTrue(claim.tryClaim())
            claim.release()
        } finally {
            executor.shutdownNow()
        }
    }

	@Test
	fun promptsForRebootOnlyWhenRunningCacheRecoveryIsDegraded() {
		assertNotNull(
			ModuleStatus(actualState = "running", androidDnsCacheFlush = "degraded")
				.dnsCacheRecoveryMessage(),
		)
		assertNull(ModuleStatus(actualState = "running", androidDnsCacheFlush = "ok").dnsCacheRecoveryMessage())
		assertNull(ModuleStatus(actualState = "stopped", androidDnsCacheFlush = "degraded").dnsCacheRecoveryMessage())
	}

}
