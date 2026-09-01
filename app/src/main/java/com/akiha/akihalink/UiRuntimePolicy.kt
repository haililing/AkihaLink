package com.akiha.akihalink

import com.akiha.akihalink.root.ModuleCompatibility

enum class EnvironmentPresentation {
    CHECKING,
    READY,
    STALE,
    FAILED,
}

enum class InstalledAppsLoadState {
    NOT_REQUESTED,
    LOADING,
    LOADED,
    FAILED,
}

internal fun environmentPresentation(state: MainUiState): EnvironmentPresentation {
    val knownReady = state.compatibility == ModuleCompatibility.Compatible && state.probe?.ok == true
    return when {
        state.runtimeRefreshing -> if (knownReady) EnvironmentPresentation.READY else EnvironmentPresentation.CHECKING
        state.runtimeStale || state.runtimeRefreshError != null -> EnvironmentPresentation.STALE
        state.compatibility == null -> EnvironmentPresentation.CHECKING
        state.compatibility != ModuleCompatibility.Compatible -> EnvironmentPresentation.FAILED
        state.probe == null -> EnvironmentPresentation.CHECKING
        state.probe.ok -> EnvironmentPresentation.READY
        else -> EnvironmentPresentation.FAILED
    }
}

internal fun shouldRunAutomaticProbe(state: MainUiState): Boolean = state.probe?.ok != true

internal fun runtimeStateNeedsConfirmation(state: MainUiState): Boolean =
    state.compatibility == null || state.runtimeRefreshing || state.runtimeStale || state.runtimeRefreshError != null

internal fun shouldLoadInstalledApps(
    appForeground: Boolean,
    exclusionPageVisible: Boolean,
): Boolean = appForeground && exclusionPageVisible
