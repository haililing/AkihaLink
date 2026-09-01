package com.akiha.akihalink

import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.root.ModuleCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRuntimeSnapshotTest {
    @Test
    fun restoresValidRunningState() {
        val now = 10_000L

        val state = StartupRuntimeSnapshot(
            actualState = "running",
            startedAt = 4_000L,
            ebpfAttached = true,
            controllerReady = true,
			startupHealth = "healthy",
			systemResolverAttached = true,
			androidDnsCacheFlush = "ok",
            probeOk = true,
            mode = ProxyMode.GLOBAL.name,
            savedAt = now,
        ).toUiState(now)

        requireNotNull(state)
        assertEquals(ModuleCompatibility.Compatible, state.compatibility)
        assertEquals("running", state.status.actualState)
        assertEquals(4_000L, state.status.startedAt)
        assertTrue(state.status.ebpfAttached)
        assertTrue(state.status.controllerReady == true)
		assertTrue(state.status.systemResolverAttached)
        assertTrue(state.probe?.ok == true)
        assertEquals(ProxyMode.GLOBAL, state.mode)
        assertTrue(state.runtimeRefreshing)
        assertTrue(state.runtimeStale)
        assertFalse(state.initialLoadComplete)
        assertFalse(state.busy)
    }

    @Test
    fun rejectsExpiredState() {
        val snapshot = StartupRuntimeSnapshot(
            actualState = "stopped",
            savedAt = 1_000L,
        )

        assertNull(snapshot.toUiState(1_000L + StartupRuntimeSnapshot.MAX_AGE_MILLIS + 1L))
    }

    @Test
    fun rejectsUnknownActualState() {
        val snapshot = StartupRuntimeSnapshot(
            actualState = "starting",
            savedAt = 1_000L,
        )

        assertNull(snapshot.toUiState(1_000L))
    }

    @Test
    fun keepsCachedRuntimeStateWhenARefreshNeedsConfirmation() {
        val cached = requireNotNull(
            StartupRuntimeSnapshot(
                actualState = "running",
                ebpfAttached = true,
				startupHealth = "degraded",
				systemResolverAttached = true,
				androidDnsCacheFlush = "degraded",
                probeOk = true,
                savedAt = 1_000L,
            ).toUiState(1_000L),
        )

        val stale = cached.copy(
            runtimeRefreshing = false,
            runtimeStale = true,
            runtimeRefreshError = "status-fast timed out",
        )

        assertEquals(ModuleCompatibility.Compatible, stale.compatibility)
        assertEquals("running", stale.status.actualState)
        assertTrue(stale.status.ebpfAttached)
        assertTrue(stale.runtimeStale)
        assertFalse(stale.busy)
    }

	@Test
	fun rejectsLegacyRunningSnapshotWithoutResolverReadiness() {
		val snapshot = StartupRuntimeSnapshot(
			actualState = "running",
			ebpfAttached = true,
			savedAt = 1_000L,
		)

		assertNull(snapshot.toUiState(1_000L))
	}

    @Test
    fun preservesUnknownAndFailedProbeResultsSeparately() {
        val unknown = requireNotNull(
            StartupRuntimeSnapshot(actualState = "stopped", probeOk = null, savedAt = 1_000L)
                .toUiState(1_000L),
        )
        val failed = requireNotNull(
            StartupRuntimeSnapshot(actualState = "stopped", probeOk = false, savedAt = 1_000L)
                .toUiState(1_000L),
        )

        assertNull(unknown.probe)
        assertTrue(failed.probe?.ok == false)
        assertTrue(shouldRunAutomaticProbe(unknown))
        assertTrue(shouldRunAutomaticProbe(failed))
    }
}
