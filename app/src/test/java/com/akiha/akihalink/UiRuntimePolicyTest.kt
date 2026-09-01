package com.akiha.akihalink

import com.akiha.akihalink.root.ModuleCompatibility
import com.akiha.akihalink.root.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiRuntimePolicyTest {
    @Test
    fun installedAppsOnlyLoadOnForegroundExclusionPage() {
        assertTrue(shouldLoadInstalledApps(appForeground = true, exclusionPageVisible = true))
        assertFalse(shouldLoadInstalledApps(appForeground = false, exclusionPageVisible = true))
        assertFalse(shouldLoadInstalledApps(appForeground = true, exclusionPageVisible = false))
    }

    @Test
    fun environmentPresentationSeparatesCheckingStaleAndConfirmedFailure() {
        val checking = MainUiState(runtimeRefreshing = true, runtimeStale = true)
        assertEquals(EnvironmentPresentation.CHECKING, environmentPresentation(checking))

        val cachedReady = checking.copy(
            compatibility = ModuleCompatibility.Compatible,
            probe = ProbeResult(ok = true),
        )
        assertEquals(EnvironmentPresentation.READY, environmentPresentation(cachedReady))

        val stale = cachedReady.copy(runtimeRefreshing = false, runtimeRefreshError = "timeout")
        assertEquals(EnvironmentPresentation.STALE, environmentPresentation(stale))

        val failed = MainUiState(
            compatibility = ModuleCompatibility.Compatible,
            probe = ProbeResult(ok = false),
        )
        assertEquals(EnvironmentPresentation.FAILED, environmentPresentation(failed))
        assertEquals(
            EnvironmentPresentation.FAILED,
            environmentPresentation(MainUiState(compatibility = ModuleCompatibility.RootDenied)),
        )
    }

    @Test
    fun automaticProbeRunsForMissingOrFailedCachedResult() {
        assertTrue(shouldRunAutomaticProbe(MainUiState()))
        assertTrue(shouldRunAutomaticProbe(MainUiState(probe = ProbeResult(ok = false))))
        assertFalse(shouldRunAutomaticProbe(MainUiState(probe = ProbeResult(ok = true))))
    }

    @Test
    fun staleOrUnknownRuntimeMustBeConfirmedBeforeMutation() {
        assertTrue(runtimeStateNeedsConfirmation(MainUiState()))
        assertTrue(runtimeStateNeedsConfirmation(MainUiState(runtimeStale = true)))
        assertTrue(runtimeStateNeedsConfirmation(MainUiState(runtimeRefreshError = "timeout")))
        assertFalse(
            runtimeStateNeedsConfirmation(
                MainUiState(
                    compatibility = ModuleCompatibility.Compatible,
                    probe = ProbeResult(ok = true),
                ),
            ),
        )
    }
}
