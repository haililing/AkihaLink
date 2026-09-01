package com.akiha.akihalink

import android.content.Context
import androidx.core.content.edit
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.root.ModuleCompatibility
import com.akiha.akihalink.root.ModuleStatus
import com.akiha.akihalink.root.ProbeResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class StartupRuntimeSnapshot(
    val actualState: String,
    val startedAt: Long? = null,
    val ebpfAttached: Boolean = false,
	val controllerReady: Boolean? = null,
	val startupHealth: String = "unknown",
	val systemResolverAttached: Boolean = false,
	val androidDnsCacheFlush: String = "not_run",
	val probeOk: Boolean? = null,
    val verifierError: String? = null,
    val mode: String = ProxyMode.RULE.name,
    val savedAt: Long,
) {
    fun toUiState(now: Long = System.currentTimeMillis()): MainUiState? {
        if (now - savedAt !in 0..MAX_AGE_MILLIS) return null
        if (actualState !in setOf("running", "stopped")) return null
        if (actualState == "running" &&
            (!ebpfAttached || startupHealth !in setOf("healthy", "degraded"))
        ) return null
        return MainUiState(
            compatibility = ModuleCompatibility.Compatible,
            probe = probeOk?.let { ProbeResult(ok = it, verifierError = verifierError) },
            status = ModuleStatus(
                actualState = actualState,
                desiredState = actualState,
                startedAt = startedAt,
                ebpfAttached = ebpfAttached,
				controllerReady = controllerReady,
				startupHealth = startupHealth,
				systemResolverAttached = systemResolverAttached,
				androidDnsCacheFlush = androidDnsCacheFlush,
            ),
            mode = runCatching { ProxyMode.valueOf(mode) }.getOrDefault(ProxyMode.RULE),
            runtimeRefreshing = true,
            runtimeStale = true,
            initialLoadComplete = false,
        )
    }

    companion object {
        const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}

internal class StartupRuntimeCache(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): MainUiState? = preferences.getString(KEY_SNAPSHOT, null)
        ?.let { encoded -> runCatching { JSON.decodeFromString<StartupRuntimeSnapshot>(encoded) }.getOrNull() }
        ?.toUiState()

    fun save(state: MainUiState) {
        if (state.compatibility != ModuleCompatibility.Compatible ||
            state.status.actualState !in setOf("running", "stopped")
        ) {
            preferences.edit { remove(KEY_SNAPSHOT) }
            return
        }
        val snapshot = StartupRuntimeSnapshot(
            actualState = state.status.actualState,
            startedAt = state.status.startedAt,
            ebpfAttached = state.status.ebpfAttached,
			controllerReady = state.status.controllerReady,
			startupHealth = state.status.startupHealth,
			systemResolverAttached = state.status.systemResolverAttached,
			androidDnsCacheFlush = state.status.androidDnsCacheFlush,
            probeOk = state.probe?.ok,
            verifierError = state.probe?.verifierError,
            mode = state.mode.name,
            savedAt = System.currentTimeMillis(),
        )
        preferences.edit {
            putString(KEY_SNAPSHOT, JSON.encodeToString(StartupRuntimeSnapshot.serializer(), snapshot))
        }
    }

    private companion object {
        const val PREFERENCES = "startup_runtime"
        const val KEY_SNAPSHOT = "runtime_snapshot"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
