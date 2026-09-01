package com.akiha.akihalink

import android.content.Context
import com.akiha.akihalink.config.AppConfigPolicy
import com.akiha.akihalink.config.ConfigRequest
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.config.SingBoxConfigGenerator
import com.akiha.akihalink.data.AkihaLinkDatabase
import com.akiha.akihalink.data.AkihaLinkRepository
import com.akiha.akihalink.root.ModuleController
import com.akiha.akihalink.root.ModuleStatus
import com.akiha.akihalink.security.ClashSecretStore
import com.akiha.akihalink.security.SecretCipher
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingPackageChange(
    val uid: Int,
    val packageName: String,
)

@Serializable
internal data class PendingPackageReconciliation(
    val revision: Long = 0,
    val reconcileAll: Boolean = false,
    val changes: List<PendingPackageChange> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !reconcileAll && changes.isEmpty()
}

internal fun mergePendingPackageChange(
    current: PendingPackageReconciliation,
    uid: Int,
    packageName: String,
    reconcileAll: Boolean,
    maxPendingChanges: Int = 256,
): PendingPackageReconciliation {
    val candidates = if (current.reconcileAll || reconcileAll) {
        emptyList()
    } else {
        (current.changes + PendingPackageChange(uid, packageName))
            .distinctBy { "${it.uid}:${it.packageName}" }
    }
    val overflowed = candidates.size > maxPendingChanges
    return PendingPackageReconciliation(
        revision = current.revision + 1,
        reconcileAll = current.reconcileAll || reconcileAll || overflowed,
        changes = if (overflowed) emptyList() else candidates,
    )
}

internal class PackageReconciliationStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun enqueue(uid: Int, packageName: String, reconcileAll: Boolean): PendingPackageReconciliation =
        synchronized(LOCK) {
            val current = loadLocked()
            val updated = mergePendingPackageChange(
                current = current,
                uid = uid,
                packageName = packageName,
                reconcileAll = reconcileAll,
                maxPendingChanges = MAX_PENDING_CHANGES,
            )
            check(
                preferences.edit()
                    .putString(KEY_PENDING, JSON.encodeToString(PendingPackageReconciliation.serializer(), updated))
                    .commit(),
            ) { "无法保存待处理的应用变更" }
            updated
        }

    fun load(): PendingPackageReconciliation = synchronized(LOCK) { loadLocked() }

    fun acknowledge(revision: Long): Boolean = synchronized(LOCK) {
        val current = loadLocked()
        if (current.revision != revision) return@synchronized false
        preferences.edit().remove(KEY_PENDING).commit()
    }

    private fun loadLocked(): PendingPackageReconciliation = preferences.getString(KEY_PENDING, null)
        ?.let { encoded ->
            runCatching { JSON.decodeFromString<PendingPackageReconciliation>(encoded) }.getOrNull()
        }
        ?: PendingPackageReconciliation()

    private companion object {
        const val PREFERENCES = "package_reconciliation"
        const val KEY_PENDING = "pending"
        const val MAX_PENDING_CHANGES = 256
        val JSON = Json { ignoreUnknownKeys = true }
        val LOCK = Any()
    }
}

internal data class PackageReconciliationResult(
    val processed: Boolean,
    val status: ModuleStatus? = null,
)

internal suspend fun drainPendingPackageChanges(
    context: Context,
    maxPasses: Int = 3,
): PackageReconciliationResult {
    val applicationContext = context.applicationContext
    val store = PackageReconciliationStore(applicationContext)
    val reconciler = PackageExclusionReconciler(applicationContext)
    var processed = false
    var latestStatus: ModuleStatus? = null
    repeat(maxPasses.coerceAtLeast(1)) {
        val pending = store.load()
        if (pending.isEmpty) return PackageReconciliationResult(processed, latestStatus)
        latestStatus = reconciler.reconcile(pending) ?: latestStatus
        processed = true
        if (store.acknowledge(pending.revision)) {
            return PackageReconciliationResult(true, latestStatus)
        }
    }
    return PackageReconciliationResult(processed, latestStatus)
}

private class PackageExclusionReconciler(private val context: Context) {
    suspend fun reconcile(pending: PendingPackageReconciliation): ModuleStatus? {
        val dao = AkihaLinkDatabase.get(context).dao()
        val storedExclusions = dao.getExcludedApps()
        if (storedExclusions.isEmpty()) return null
        if (!pending.reconcileAll) {
            val changedUids = pending.changes.mapTo(hashSetOf()) { it.uid }
            val changedPackages = pending.changes.mapTo(hashSetOf()) { it.packageName }
            if (storedExclusions.none { it.uid in changedUids || it.packageName in changedPackages }) return null
        }

        val cipher = SecretCipher()
        val repository = AkihaLinkRepository(dao, cipher)
        val controller = ModuleController(context)
        var status = controller.statusFast()
        var attempts = 0
        while (status.actualState == "starting" && attempts < STARTING_STATUS_ATTEMPTS) {
            delay(STARTING_STATUS_RETRY_MILLIS)
            status = controller.statusFast()
            attempts++
        }
        if (status.actualState != "running") return status

        var hotspotProxyEnabled = repository.hotspotProxyEnabled()
        if (hotspotProxyEnabled && status.hotspotProxyState == "failed") {
            repository.setHotspotProxyEnabled(false)
            hotspotProxyEnabled = false
        }

        val mode = runCatching { ProxyMode.valueOf(repository.mode().uppercase()) }
            .getOrDefault(ProxyMode.RULE)
        if (mode == ProxyMode.DIRECT) return status
        val nodes = repository.allNodes()
        val selectedNodeId = repository.selectedNodeId() ?: return status
        val installedPackages = AndroidInstalledAppCatalog(context).load()
        val excludedUids = resolveExcludedApps(storedExclusions, installedPackages).effectiveUids
        val config = SingBoxConfigGenerator().generate(
            ConfigRequest(
                mode = mode,
                nodes = nodes.map(repository::decryptNode),
                selectedNodeId = selectedNodeId,
                excludedUids = excludedUids,
                appUid = context.applicationInfo.uid,
                androidUserIds = context.getSystemService(android.os.UserManager::class.java).userProfiles
                    .mapTo(hashSetOf()) { it.hashCode() }
                    .ifEmpty { setOf(0) },
                clashSecret = ClashSecretStore(context, cipher).getOrCreate(),
                runtimeOptions = AppConfigPolicy.runtimeOptions.copy(
                    reverseDnsMapping = repository.reverseDnsMappingEnabled(),
                ),
                hotspotProxyEnabled = hotspotProxyEnabled,
            ),
        )
        val changedPackages = pending.changes.mapTo(hashSetOf()) { it.packageName }
        val affectedUids = buildSet {
            if (pending.reconcileAll) {
                storedExclusions.mapTo(this) { it.uid }
                addAll(excludedUids)
            } else {
                pending.changes.mapTo(this) { it.uid }
                storedExclusions.asSequence()
                    .filter { it.packageName in changedPackages }
                    .mapTo(this) { it.uid }
                installedPackages.asSequence()
                    .filter { it.packageName in changedPackages }
                    .mapTo(this) { it.uid }
            }
        }
        val result = controller.applyExclusions(config, affectedUids)
        check(result.policyStatus.known && result.policyStatus.inSync) {
            "UID policy did not reach a verified kernel state"
        }
        return result.runningStatus
    }

    private companion object {
        const val STARTING_STATUS_ATTEMPTS = 12
        const val STARTING_STATUS_RETRY_MILLIS = 500L
    }
}
