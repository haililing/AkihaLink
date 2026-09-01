package com.akiha.akihalink

import android.app.Application
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akiha.akihalink.clash.ClashApiClient
import com.akiha.akihalink.config.AndroidUidPolicy
import com.akiha.akihalink.config.AppConfigPolicy
import com.akiha.akihalink.config.ConfigRequest
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.config.SingBoxConfigGenerator
import com.akiha.akihalink.config.SpeedTestConfigGenerator
import com.akiha.akihalink.config.SpeedTestConfigRequest
import com.akiha.akihalink.config.UidPolicySummary
import com.akiha.akihalink.data.AkihaLinkDatabase
import com.akiha.akihalink.data.AkihaLinkRepository
import com.akiha.akihalink.data.ExcludedAppEntity
import com.akiha.akihalink.data.NodeSort
import com.akiha.akihalink.data.ProxyNodeEntity
import com.akiha.akihalink.data.SubscriptionEntity
import com.akiha.akihalink.data.SubscriptionUpdateResult
import com.akiha.akihalink.root.ModuleCompatibility
import com.akiha.akihalink.root.ModuleController
import com.akiha.akihalink.root.ModuleStatus
import com.akiha.akihalink.root.ActivationException
import com.akiha.akihalink.root.UidPolicyStatus
import com.akiha.akihalink.root.ProbeResult
import com.akiha.akihalink.performance.PerformanceTrace
import com.akiha.akihalink.security.SecretCipher
import com.akiha.akihalink.security.ClashSecretStore
import com.akiha.akihalink.speedtest.NodeLatencyResult
import com.akiha.akihalink.speedtest.NodeSpeedTestRunner
import com.akiha.akihalink.speedtest.SpeedTestTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private enum class EnvironmentRefreshOrigin { AUTO, MANUAL }

private enum class RuntimeRefreshPolicy { NONE, REQUIRE_FRESH_IF_STALE }

enum class DnsValidationState {
    IDLE,
    CHECKING,
    HEALTHY,
    DEGRADED,
}

internal fun toggleExclusionDraft(
    persisted: Set<Int>,
    draft: Set<Int>?,
    uid: Int,
): Set<Int> = (draft ?: persisted).toMutableSet().apply {
    if (!add(uid)) remove(uid)
}

data class MainUiState(
    val compatibility: ModuleCompatibility? = null,
    val probe: ProbeResult? = null,
    val status: ModuleStatus = ModuleStatus(),
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val subscriptionNodeCounts: Map<String, Int> = emptyMap(),
    val nodes: List<ProxyNodeEntity> = emptyList(),
    val excludedApps: List<ExcludedAppEntity> = emptyList(),
    val installedApps: List<InstalledApp> = emptyList(),
    val installedAppsLoadState: InstalledAppsLoadState = InstalledAppsLoadState.NOT_REQUESTED,
    val installedAppsLoadError: String? = null,
    val excludedAppDraftUids: Set<Int>? = null,
    val selectedNodeId: String? = null,
    val mode: ProxyMode = ProxyMode.RULE,
    val reverseDnsMappingEnabled: Boolean = AppConfigPolicy.runtimeOptions.reverseDnsMapping,
    val hotspotProxyEnabled: Boolean = false,
    val nodeLatencyResults: Map<String, NodeLatencyResult> = emptyMap(),
    val nodeSort: NodeSort = NodeSort.NAME,
    val speedTestRunning: Boolean = false,
    val speedTestCompleted: Int = 0,
    val speedTestTotal: Int = 0,
    val testingNodeIds: Set<String> = emptySet(),
    val uidPolicySummary: UidPolicySummary = UidPolicySummary(),
    val uidPolicyStatus: UidPolicyStatus = UidPolicyStatus(),
    val dnsValidation: DnsValidationState = DnsValidationState.IDLE,
    val dnsValidationNodeId: String? = null,
    val dnsValidationGeneration: Long = 0,
    val busy: Boolean = false,
    val runtimeRefreshing: Boolean = false,
    val runtimeStale: Boolean = false,
    val runtimeRefreshError: String? = null,
    val message: String? = null,
    val initialLoadComplete: Boolean = false,
)

internal fun applyNodeDnsValidation(
    state: MainUiState,
    nodeId: String,
    generation: Long,
    validation: DnsValidationState,
): MainUiState = if (
    state.selectedNodeId != nodeId ||
    state.dnsValidationNodeId != nodeId ||
    state.dnsValidationGeneration != generation
) {
    state
} else {
    state.copy(dnsValidation = validation)
}

internal fun ModuleStatus.proxyMayBeActive(): Boolean =
    desiredState == "running" || actualState == "running" || actualState == "starting"

internal fun ModuleStatus.retainControllerReadiness(previous: ModuleStatus): ModuleStatus =
    if (actualState == "running" && controllerReady == null && sameRuntimeInstance(previous)) {
        copy(controllerReady = previous.controllerReady)
    } else {
        this
    }

internal fun ModuleStatus.sameRuntimeInstance(other: ModuleStatus): Boolean =
    actualState == other.actualState &&
        (actualState != "running" || startedAt == null || other.startedAt == null || startedAt == other.startedAt)

internal fun ModuleStatus.nodeSwitchAllowed(): Boolean = actualState != "starting"

internal class SpeedTestJobClaim {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)

    fun release() {
        check(claimed.compareAndSet(true, false)) { "Speed test job claim was not held" }
    }
}

internal fun ModuleStatus.dnsCacheRecoveryMessage(): String? =
    if (actualState == "running" && androidDnsCacheFlush == "degraded") {
        "Android DNS 缓存清理失败，建议重启设备后重新测试"
    } else {
        null
    }

private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}

private enum class InitialLoadPart {
    SUBSCRIPTIONS,
    NODES,
    NODE_COUNTS,
    EXCLUSIONS,
    SETTINGS,
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val cipher = SecretCipher()
    private val repository = AkihaLinkRepository(AkihaLinkDatabase.get(application).dao(), cipher)
    private val controller = ModuleController(application)
    private val clashApi = ClashApiClient()
    private val generator = SingBoxConfigGenerator()
    private val speedTestGenerator = SpeedTestConfigGenerator()
    private val installedAppCatalog = AndroidInstalledAppCatalog(application)
    private val clashSecret = ClashSecretStore(application, cipher).getOrCreate()
    private val startupRuntimeCache = StartupRuntimeCache(application)
    private val runtimeCoordinator = RuntimeCoordinator()
    private val environmentRefreshRunner = SupersedingJobRunner()
    private var environmentBusyToken: Long? = null
    private var speedTestJob: Job? = null
    private var installedAppsJob: Job? = null
    private var installedAppsLoadGeneration: Long = 0
    private var hotspotStatePollJob: Job? = null
    private var dnsValidationJob: Job? = null
    private var dnsValidationGeneration: Long = 0
    private val actionMutex = RuntimeOperationGate.mutex
    private val speedTestJobClaim = SpeedTestJobClaim()
    private val persistenceReady = CompletableDeferred<Unit>()
    private val initialLoadParts = mutableSetOf<InitialLoadPart>()
    @Volatile private var appForeground = false
    @Volatile private var appExclusionVisible = false

    private val _state = MutableStateFlow(
        startupRuntimeCache.load() ?: MainUiState(
            runtimeRefreshing = true,
            runtimeStale = true,
            initialLoadComplete = false,
        ),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.subscriptions.collect { value ->
                _state.update { it.copy(subscriptions = value) }
                markInitialLoadComplete(InitialLoadPart.SUBSCRIPTIONS)
            }
        }
        viewModelScope.launch {
            repository.nodes.collect { value ->
                _state.update { it.copy(nodes = value) }
                markInitialLoadComplete(InitialLoadPart.NODES)
            }
        }
        viewModelScope.launch {
            repository.subscriptionNodeCounts.collect { value ->
                _state.update {
                    it.copy(subscriptionNodeCounts = value.associate { count ->
                        count.subscriptionId to count.nodeCount
                    })
                }
                markInitialLoadComplete(InitialLoadPart.NODE_COUNTS)
            }
        }
        viewModelScope.launch {
            repository.excludedApps.collect { value ->
                _state.update { it.copy(excludedApps = value) }
                markInitialLoadComplete(InitialLoadPart.EXCLUSIONS)
                val installedPackages = runSuspendCatching { installedAppCatalog.load() }.getOrNull()
                    ?: return@collect
                val excludedUids = resolveExcludedApps(value, installedPackages).effectiveUids
                val uidPolicySummary = buildUidPolicySummary(excludedUids)
                _state.update { current ->
                    current.copy(
                        excludedApps = value,
                        uidPolicySummary = uidPolicySummary,
                        installedApps = if (current.installedApps.isEmpty()) emptyList() else {
                            sortInstalledApps(groupInstalledApps(installedPackages, excludedUids))
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            try {
                var selected = repository.selectedNodeId()
                if (selected != null && repository.allNodes().none { it.id == selected }) {
                    repository.clearSelectedNode()
                    selected = null
                }
                val mode = runCatching { ProxyMode.valueOf(repository.mode().uppercase()) }
                    .getOrDefault(ProxyMode.RULE)
                val reverseDnsMappingEnabled = repository.reverseDnsMappingEnabled()
                val hotspotProxyEnabled = repository.hotspotProxyEnabled()
                val nodeLatencyResults = repository.nodeLatencyResults()
                repository.cleanupLegacyPerformanceSettings()
                val nodeSort = repository.nodeSort()
                _state.update { current ->
                    current.copy(
                        selectedNodeId = selected,
                        mode = mode,
                        reverseDnsMappingEnabled = reverseDnsMappingEnabled,
                        hotspotProxyEnabled = hotspotProxyEnabled,
                        nodeLatencyResults = nodeLatencyResults,
                        nodeSort = nodeSort,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(message = error.message?.take(500) ?: "本地设置读取失败")
                }
            } finally {
                persistenceReady.complete(Unit)
                markInitialLoadComplete(InitialLoadPart.SETTINGS)
            }
        }
    }

    private fun markInitialLoadComplete(part: InitialLoadPart) {
        if (!initialLoadParts.add(part) || initialLoadParts.size != InitialLoadPart.entries.size) return
        _state.update { it.copy(initialLoadComplete = true) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun checkEnvironment() = startEnvironmentRefresh(EnvironmentRefreshOrigin.MANUAL)

    private fun startEnvironmentRefresh(origin: EnvironmentRefreshOrigin) {
        val userInitiated = origin == EnvironmentRefreshOrigin.MANUAL
        if (!appForeground || (userInitiated && _state.value.busy)) return
        environmentRefreshRunner.launch(viewModelScope) { token ->
            persistenceReady.await()
            if (!appForeground || !environmentRefreshRunner.isCurrent(token)) return@launch
            actionMutex.lock()
            var ownsBusy = false
            try {
                if (!appForeground || !environmentRefreshRunner.isCurrent(token)) return@launch
                if (userInitiated) {
                    environmentBusyToken = token
                    ownsBusy = true
                    _state.update { it.copy(busy = true, message = null) }
                }
                val runProbe = userInitiated || shouldRunAutomaticProbe(_state.value)
                _state.update {
                    it.copy(
                        runtimeRefreshing = true,
                        runtimeRefreshError = null,
                    )
                }
                val compatibility = controller.compatibility()
                if (compatibility is ModuleCompatibility.Error) {
                    throw IllegalStateException(compatibility.message)
                }
                if (!environmentRefreshRunner.isCurrent(token)) return@launch
                _state.update {
                    it.copy(
                        compatibility = compatibility,
                        probe = if (compatibility == ModuleCompatibility.Compatible) it.probe else null,
                    )
                }
                if (compatibility == ModuleCompatibility.Compatible) {
                    var status = controller.statusFast().retainControllerReadiness(_state.value.status)
                    if (status.proxyMayBeActive() && _state.value.selectedNodeId == null) {
                        status = controller.stop(_state.value.mode.wireName)
                    }
                    if (!environmentRefreshRunner.isCurrent(token)) return@launch
                    _state.update {
                        it.copy(
                            status = status,
                            runtimeStale = false,
                            runtimeRefreshError = null,
                            message = status.dnsCacheRecoveryMessage() ?: it.message,
                        )
                    }
                    retryPendingPackageReconciliation()?.let { reconciledStatus ->
                        status = reconciledStatus
                        _state.update { it.copy(status = reconciledStatus) }
                    }
                    syncFailedHotspotSetting(status)
                    if (status.proxyMayBeActive()) {
                        refreshRuntimeHealth()
                        if (!environmentRefreshRunner.isCurrent(token)) return@launch
                    }

                    if (runProbe) {
                        val probe = runSuspendCatching { controller.probe() }.getOrElse { error ->
                            ProbeResult(ok = false, verifierError = error.message?.take(500) ?: "eBPF probe 失败")
                        }
                        if (!environmentRefreshRunner.isCurrent(token)) return@launch
                        _state.update { it.copy(probe = probe) }
                    }
                    saveStartupRuntimeSnapshot()
                } else {
                    _state.update {
                        it.copy(
                            status = ModuleStatus(),
                            probe = null,
                            runtimeStale = false,
                            runtimeRefreshError = null,
                        )
                    }
                    saveStartupRuntimeSnapshot()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val text = error.message?.take(500) ?: "运行状态同步失败"
                if (environmentRefreshRunner.isCurrent(token)) _state.update {
                    it.copy(
                        runtimeStale = true,
                        runtimeRefreshError = text,
                        message = if (userInitiated) text else it.message,
                    )
                }
            } finally {
                if (environmentRefreshRunner.isCurrent(token)) {
                    _state.update {
                        it.copy(
                            busy = if (ownsBusy && environmentBusyToken == token) false else it.busy,
                            runtimeRefreshing = false,
                        )
                    }
                    if (environmentBusyToken == token) environmentBusyToken = null
                }
                actionMutex.unlock()
            }
        }
    }

    fun addSubscription(name: String, url: String) = launchBusy {
        cancelSpeedTestForMutation()
        val subscription = repository.addSubscription(name, url)
        try {
            if (_state.value.selectedNodeId != null &&
                _state.value.mode != ProxyMode.DIRECT &&
                _state.value.compatibility == ModuleCompatibility.Compatible
            ) {
                applyConfig(buildConfig(_state.value.mode, repository.allNodes()))
            }
        } catch (error: Throwable) {
            repository.deleteSubscription(subscription)
            throw IllegalStateException("订阅配置验证失败，未保存：${error.message.orEmpty().take(200)}", error)
        }
        pruneLatencyResults()
        _state.update { it.copy(message = "订阅已添加，请选择节点") }
    }

    fun updateSubscription(subscription: SubscriptionEntity) = launchBusy {
        cancelSpeedTestForMutation()
        when (val update = repository.updateSubscription(subscription)) {
            is SubscriptionUpdateResult.Failed -> {
                _state.update { it.copy(message = update.message) }
            }
            is SubscriptionUpdateResult.Updated -> {
                if (!update.selectionStillValid) {
                    try {
                        val currentStatus = _state.value.status
                        val status = if (currentStatus.proxyMayBeActive()) {
                            controller.stop(_state.value.mode.wireName)
                        } else {
                            currentStatus
                        }
                        _state.update {
                            it.copy(
                                selectedNodeId = null,
                                status = status,
                                message = "更新完成，当前节点需要重新选择",
                            )
                        }
                        saveStartupRuntimeSnapshot()
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            repository.restoreSubscription(update, "停止旧节点失败，已恢复上一份订阅")
                            _state.update { it.copy(selectedNodeId = update.previousSelectedNodeId) }
                            refreshRuntimeStatus()
                        }
                        throw error
                    }
                } else if (
                    subscription.isEnabled &&
                    _state.value.selectedNodeId != null &&
                    _state.value.mode != ProxyMode.DIRECT &&
                    _state.value.compatibility == ModuleCompatibility.Compatible
                ) {
                    try {
                        applyConfig(buildConfig(_state.value.mode, repository.allNodes()))
                    } catch (error: Throwable) {
                        val reason = "新配置应用失败，已恢复上一份订阅"
                        repository.restoreSubscription(update, reason)
                        refreshRuntimeStatus()
                        throw IllegalStateException("$reason：${error.message.orEmpty().take(200)}", error)
                    }
                }
                if (update.selectionStillValid) {
                    _state.update { it.copy(message = "订阅已更新") }
                }
                pruneLatencyResults()
            }
        }
    }

    fun setSubscriptionEnabled(subscription: SubscriptionEntity, enabled: Boolean) = launchBusy {
        cancelSpeedTestForMutation()
        if (subscription.isEnabled == enabled) return@launchBusy
        val change = repository.setSubscriptionEnabled(subscription, enabled)
        try {
            if (change.selectionCleared) {
                val status = if (_state.value.status.proxyMayBeActive()) {
                    controller.stop(_state.value.mode.wireName)
                } else {
                    _state.value.status
                }
                _state.update {
                    it.copy(
                        selectedNodeId = null,
                        status = status,
                        message = "订阅已关闭，请重新选择节点",
                    )
                }
                saveStartupRuntimeSnapshot()
            } else if (_state.value.status.proxyMayBeActive()) {
                val nodes = repository.allNodes()
                val status = if (_state.value.selectedNodeId == null || nodes.isEmpty()) {
                    controller.stop(_state.value.mode.wireName)
                } else {
                    applyConfig(buildConfig(_state.value.mode, nodes))
                }
                _state.update {
                    it.copy(
                        status = status,
                        message = if (enabled) "订阅已启用" else "订阅已关闭",
                    )
                }
            } else {
                _state.update {
                    it.copy(message = if (enabled) "订阅已启用" else "订阅已关闭")
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                repository.restoreSubscriptionEnabled(change)
                _state.update { it.copy(selectedNodeId = change.selectedNodeId) }
                refreshRuntimeStatus()
            }
            throw error
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) = launchBusy {
        cancelSpeedTestForMutation()
        val deletion = repository.deleteSubscription(subscription)
        try {
            if (deletion.selectedRemoved) {
                val status = if (_state.value.status.proxyMayBeActive()) {
                    controller.stop(_state.value.mode.wireName)
                } else _state.value.status
                _state.update {
                    it.copy(
                        selectedNodeId = null,
                        status = status,
                        message = "订阅已删除，请重新选择节点",
                    )
                }
            } else if (subscription.isEnabled && _state.value.status.proxyMayBeActive()) {
                val nodes = repository.allNodes()
                val status = if (nodes.isEmpty()) {
                    controller.stop(_state.value.mode.wireName)
                } else {
                    applyConfig(buildConfig(_state.value.mode, nodes))
                }
                _state.update { it.copy(status = status, message = "订阅已删除") }
            } else {
                _state.update { it.copy(message = "订阅已删除") }
            }
            pruneLatencyResults()
        } catch (error: Throwable) {
            repository.restoreSubscription(deletion)
            refreshRuntimeStatus()
            throw error
        }
    }

    fun selectNode(entity: ProxyNodeEntity) = launchBusy(
        refreshPolicy = RuntimeRefreshPolicy.REQUIRE_FRESH_IF_STALE,
        traceName = "AKL/node_switch",
    ) {
            cancelSpeedTestForMutation()
            val node = repository.decryptNode(entity)
            val previousNodeId = _state.value.selectedNodeId
            val previousNode = _state.value.nodes.firstOrNull { it.id == previousNodeId }
            require(_state.value.status.nodeSwitchAllowed()) {
                "代理正在启动，请在连接完成后切换节点"
            }
            val running = _state.value.status.actualState == "running"
            cancelNodeDnsValidation()
            if (running) {
                try {
                    clashApi.selectNode(
                        clashSecret,
                        SingBoxConfigGenerator.SELECTOR_TAG,
                        SingBoxConfigGenerator.nodeTag(node),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    refreshRuntimeHealth()
                    throw error
                }
            }
            try {
                repository.selectNode(entity.id)
            } catch (error: Throwable) {
                if (running && previousNode != null) {
                    withContext(NonCancellable) {
                        runCatching {
                            clashApi.selectNode(
                                clashSecret,
                                SingBoxConfigGenerator.SELECTOR_TAG,
                                SingBoxConfigGenerator.nodeTag(previousNode.id),
                            )
                        }
                    }
                }
                throw error
            }
            val validationGeneration = if (running) {
                ++dnsValidationGeneration
            } else {
                dnsValidationGeneration
            }
            _state.update {
                it.copy(
                    selectedNodeId = entity.id,
                    message = "已选择 ${entity.name}",
                    dnsValidation = if (running) DnsValidationState.CHECKING else DnsValidationState.IDLE,
                    dnsValidationNodeId = entity.id.takeIf { running },
                    dnsValidationGeneration = validationGeneration,
                )
            }
            if (running) {
                val nodeTag = SingBoxConfigGenerator.nodeTag(node)
                startNodeDnsValidation(entity.id, nodeTag, validationGeneration)
                viewModelScope.launch {
                    runCatching { controller.updateTelemetryTarget(nodeTag, node.fingerprint) }
                }
            }
    }

    fun setNodeSort(sort: NodeSort) {
        val previous = _state.value.nodeSort
        if (previous == sort) return
        _state.update { it.copy(nodeSort = sort) }
        viewModelScope.launch {
            runSuspendCatching { repository.setNodeSort(sort) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            nodeSort = if (it.nodeSort == sort) previous else it.nodeSort,
                            message = error.message?.take(500) ?: "排序设置保存失败",
                        )
                    }
                }
        }
    }

    fun startSpeedTest() {
        val initial = _state.value
        if (speedTestJob?.isActive == true || initial.speedTestRunning || initial.busy) return
        if (initial.nodes.isEmpty()) {
            _state.update { it.copy(message = "没有可测速的节点") }
            return
        }
        if (initial.compatibility != ModuleCompatibility.Compatible) {
            _state.update { it.copy(message = "模块版本不兼容，无法测速") }
            return
        }

        val nodeEntities = initial.nodes.toList()
        val targets = nodeEntities.map { entity ->
            SpeedTestTarget(entity.id, SingBoxConfigGenerator.nodeTag(entity.id))
        }
        val targetIds = targets.mapTo(hashSetOf()) { it.nodeId }
        if (!speedTestJobClaim.tryClaim()) return
        _state.update {
            it.copy(
                speedTestRunning = true,
                speedTestCompleted = 0,
                speedTestTotal = targets.size,
                testingNodeIds = targets.mapTo(linkedSetOf()) { target -> target.nodeId },
                message = null,
            )
        }
        speedTestJob = viewModelScope.launch {
            var auxiliary = false
            var completedNormally = false
            var resultsPersisted = false
            var setupLockHeld = false
            val completedResults = ConcurrentHashMap<String, NodeLatencyResult>()
            suspend fun persistCompletedResults() {
                if (resultsPersisted || completedResults.isEmpty()) return
                withContext(NonCancellable) {
                    val resultSnapshot = completedResults.toSortedMap()
                    repository.saveNodeLatencyResults(resultSnapshot)
                }
                resultsPersisted = true
            }
            try {
                persistenceReady.await()
                actionMutex.lock()
                setupLockHeld = true
                ensureRuntimeStateFreshForMutation()
                require(_state.value.compatibility == ModuleCompatibility.Compatible) {
                    "模块版本不兼容，无法测速"
                }
                val port = if (_state.value.status.actualState == "running") {
                    MAIN_CLASH_PORT
                } else {
                    auxiliary = true
                    val config = withContext(Dispatchers.Default) { buildSpeedTestConfig(nodeEntities) }
                    val session = controller.startSpeedTest(config)
                    require(session.running) { session.lastError ?: "测速核心未启动" }
                    clashApi.awaitReady(clashSecret, session.port)
                    session.port
                }
                actionMutex.unlock()
                setupLockHeld = false
                val healthCheckClaimed = AtomicBoolean(false)
                val runner = NodeSpeedTestRunner(
                    probe = { tag ->
                        try {
                            clashApi.delay(clashSecret, port, tag)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            if (port == MAIN_CLASH_PORT && healthCheckClaimed.compareAndSet(false, true)) {
                                refreshRuntimeHealth()
                            }
                            throw error
                        }
                    },
                )
                runner.run(targets) { target, result ->
                    completedResults[target.nodeId] = result
                    _state.update { current ->
                        current.copy(
                            nodeLatencyResults = current.nodeLatencyResults + (target.nodeId to result),
                            speedTestCompleted = current.speedTestCompleted + 1,
                            testingNodeIds = current.testingNodeIds - target.nodeId,
                        )
                    }
                }
                persistCompletedResults()
                completedNormally = true
                repository.setNodeSort(NodeSort.LATENCY)
                val results = _state.value.nodeLatencyResults.filterKeys { it in targetIds }
                val available = results.values.count { it.delayMs != null }
                _state.update {
                    it.copy(
                        nodeSort = NodeSort.LATENCY,
                        message = "测速完成：$available/${targets.size} 个节点可用",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.message?.take(500) ?: "批量测速失败") }
            } finally {
                runCatching { persistCompletedResults() }.onFailure { error ->
                    _state.update { it.copy(message = error.message?.take(500) ?: "测速结果保存失败") }
                }
                if (auxiliary) {
                    withContext(NonCancellable) { runCatching { controller.stopSpeedTest() } }
                }
                if (setupLockHeld) actionMutex.unlock()
                _state.update { current ->
                    current.copy(
                        speedTestRunning = false,
                        testingNodeIds = emptySet(),
                        message = current.message ?: if (!completedNormally) {
                            "测速已取消，已保存 ${current.speedTestCompleted} 个结果"
                        } else null,
                    )
                }
                speedTestJob = null
                speedTestJobClaim.release()
            }
        }
    }

    fun cancelSpeedTest() {
        speedTestJob?.takeIf { it.isActive }?.cancel()
    }

    fun onAppBackgrounded() {
        appForeground = false
        saveStartupRuntimeSnapshot()
        environmentRefreshRunner.cancel()
        val environmentOwnedBusy = environmentBusyToken != null
        environmentBusyToken = null
        _state.update {
            it.copy(
                busy = if (environmentOwnedBusy) false else it.busy,
                runtimeRefreshing = false,
            )
        }
        cancelSpeedTest()
        cancelNodeDnsValidation()
        installedAppsLoadGeneration++
        installedAppsJob?.cancel()
        installedAppsJob = null
        _state.update { current ->
            if (current.installedAppsLoadState == InstalledAppsLoadState.LOADING) {
                current.copy(installedAppsLoadState = InstalledAppsLoadState.NOT_REQUESTED)
            } else {
                current
            }
        }
        hotspotStatePollJob?.cancel()
        hotspotStatePollJob = null
    }

    fun onAppForegrounded() {
        appForeground = true
        startEnvironmentRefresh(EnvironmentRefreshOrigin.AUTO)
        startHotspotStatePolling()
        if (appExclusionVisible) loadInstalledApps()
    }

    fun setAppExclusionVisible(visible: Boolean) {
        appExclusionVisible = visible
        if (visible) {
            loadInstalledApps()
        } else {
            installedAppsLoadGeneration++
            installedAppsJob?.cancel()
            installedAppsJob = null
            _state.update { current ->
                if (current.installedAppsLoadState == InstalledAppsLoadState.LOADING) {
                    current.copy(installedAppsLoadState = InstalledAppsLoadState.NOT_REQUESTED)
                } else {
                    current
                }
            }
        }
    }

    fun retryInstalledApps() {
        if (!appExclusionVisible || _state.value.installedAppsLoadState == InstalledAppsLoadState.LOADING) return
        loadInstalledApps()
    }

    fun setMode(mode: ProxyMode) = launchBusy {
        cancelSpeedTestForMutation()
        cancelNodeDnsValidation()
        val previous = _state.value.mode
        if (previous == mode) return@launchBusy
        repository.setMode(mode.wireName)
        _state.update { it.copy(mode = mode) }
        try {
            if (mode == ProxyMode.DIRECT) {
                val status = controller.stop(mode.wireName)
                _state.update { it.copy(status = status, message = "已切换为直连") }
            } else if (_state.value.status.proxyMayBeActive()) {
                val status = applyConfig(buildConfig(mode))
                _state.update { it.copy(status = status, message = "模式已切换") }
            } else if (_state.value.compatibility == ModuleCompatibility.Compatible) {
                val status = controller.stop(mode.wireName)
                _state.update { it.copy(status = status) }
            }
            saveStartupRuntimeSnapshot()
        } catch (error: Throwable) {
            repository.setMode(previous.wireName)
            val status = refreshRuntimeStatusFast(showFailure = false)
            _state.update { it.copy(mode = previous, status = status) }
            saveStartupRuntimeSnapshot()
            throw error
        }
    }

    fun toggleReverseDnsMapping() = launchBusy {
        cancelSpeedTestForMutation()
        cancelNodeDnsValidation()
        val current = _state.value
        val previous = current.reverseDnsMappingEnabled
        val enabled = !previous
        repository.setReverseDnsMappingEnabled(enabled)
        _state.update { it.copy(reverseDnsMappingEnabled = enabled) }
        try {
            if (current.mode == ProxyMode.RULE && _state.value.status.proxyMayBeActive()) {
                val status = applyConfig(buildConfig(current.mode))
                _state.update { it.copy(status = status) }
            }
            _state.update {
                it.copy(message = if (enabled) "域名转 IP 已开启" else "域名转 IP 已关闭")
            }
            saveStartupRuntimeSnapshot()
        } catch (error: Throwable) {
            repository.setReverseDnsMappingEnabled(previous)
            val status = refreshRuntimeStatusFast(showFailure = false)
            _state.update {
                it.copy(
                    reverseDnsMappingEnabled = previous,
                    status = status,
                )
            }
            saveStartupRuntimeSnapshot()
            throw error
        }
    }

    fun toggleHotspotProxy() = launchBusy {
        cancelSpeedTestForMutation()
        val current = _state.value
        val previous = current.hotspotProxyEnabled
        val enabled = !previous
        if (enabled && !current.status.proxyMayBeActive()) {
            val supported = runSuspendCatching { controller.probeHotspot().supported }.getOrDefault(false)
            val saved = runSuspendCatching { repository.setHotspotProxyEnabled(supported) }.isSuccess
            _state.update { it.copy(hotspotProxyEnabled = supported && saved, message = null) }
            saveStartupRuntimeSnapshot()
            return@launchBusy
        }

        try {
            if (current.status.proxyMayBeActive()) {
                val status = applyConfig(buildConfig(current.mode, hotspotProxyEnabled = enabled))
                check(!enabled || status.hotspotProxyState != "failed")
                _state.update { it.copy(status = status) }
            }
            repository.setHotspotProxyEnabled(enabled)
            _state.update { it.copy(hotspotProxyEnabled = enabled) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (enabled && current.status.proxyMayBeActive()) {
                runSuspendCatching {
                    applyConfig(buildConfig(current.mode, hotspotProxyEnabled = false))
                }
            }
            runSuspendCatching { repository.setHotspotProxyEnabled(previous) }
            _state.update { it.copy(hotspotProxyEnabled = previous, message = null) }
        }
        saveStartupRuntimeSnapshot()
    }

    fun togglePower() {
        val trace = PerformanceTrace.create()
        launchBusy(
            refreshPolicy = RuntimeRefreshPolicy.REQUIRE_FRESH_IF_STALE,
            traceName = "AKL/connect_total",
            traceContext = trace,
        ) {
            cancelSpeedTestForMutation()
            cancelNodeDnsValidation()
            val current = _state.value
            val status = if (current.status.proxyMayBeActive()) {
                controller.stop(current.mode.wireName)
            } else {
                require(current.compatibility == ModuleCompatibility.Compatible) { "模块版本不兼容" }
                val probe = current.probe?.takeIf { it.ok } ?: runSuspendCatching { controller.probe() }
                    .getOrElse { error ->
                        ProbeResult(ok = false, verifierError = error.message?.take(500) ?: "eBPF probe 失败")
                    }
                _state.update { it.copy(probe = probe) }
                require(probe.ok) { probe.verifierError ?: "eBPF probe 未通过" }
                require(current.mode != ProxyMode.DIRECT) { "直连模式不会启动代理核心" }
                var hotspotEnabled = current.hotspotProxyEnabled
                if (hotspotEnabled) {
                    hotspotEnabled = runSuspendCatching { controller.probeHotspot().supported }.getOrDefault(false)
                    if (!hotspotEnabled) disableHotspotSetting()
                }
                val activation = try {
                    activateCurrentConfig(current.mode, hotspotEnabled, trace)
                } catch (hotspotError: Throwable) {
                    if (!hotspotEnabled || hotspotError is CancellationException) throw hotspotError
                    disableHotspotSetting()
                    activateCurrentConfig(current.mode, false, trace)
                }
                if (hotspotEnabled && activation.status.hotspotProxyState == "failed") {
                    disableHotspotSetting()
                    applyConfig(buildConfig(current.mode, hotspotProxyEnabled = false))
                } else {
                    activation.status
                }
            }
            _state.update {
                it.copy(
                    status = status,
                    runtimeStale = false,
                    runtimeRefreshError = null,
                    message = status.dnsCacheRecoveryMessage() ?: it.message,
                )
            }
            if (status.actualState == "running") syncTelemetryTarget()
            saveStartupRuntimeSnapshot()
        }
    }

    fun toggleExcludedAppDraft(uid: Int) = _state.update { current ->
        if (current.installedApps.none { it.uid == uid }) return@update current
        val persisted = current.installedApps.filterTo(mutableListOf()) { it.excluded }
            .mapTo(hashSetOf()) { it.uid }
        current.copy(
            excludedAppDraftUids = toggleExclusionDraft(persisted, current.excludedAppDraftUids, uid),
        )
    }

    fun discardExcludedAppDraft() = _state.update { it.copy(excludedAppDraftUids = null) }

    fun applyExcludedAppDraft() = launchBusy {
        val current = _state.value
        val selectedUids = current.excludedAppDraftUids ?: return@launchBusy
        val storedPrevious = repository.allExcludedApps()
        val installedPackages = installedAppCatalog.load()
        val previousResolution = resolveExcludedApps(storedPrevious, installedPackages)
        val previous = previousResolution.matched
        val previousUids = previousResolution.effectiveUids
        if (selectedUids == previousUids) {
            _state.update { it.copy(excludedAppDraftUids = null) }
            return@launchBusy
        }

        val availableUids = installedPackages.mapTo(hashSetOf()) { it.uid }
        val replacement = exclusionsForUids(selectedUids, installedPackages)
        require(selectedUids.all(availableUids::contains)) { "应用列表已变化，请重新打开此页面" }

        cancelSpeedTestForMutation()
        val wasRunning = current.status.proxyMayBeActive()
        val affectedUids = (selectedUids - previousUids) + (previousUids - selectedUids) +
            previousResolution.stale.map { it.uid }
        val config = if (wasRunning) {
            buildConfig(current.mode, excludedUids = selectedUids)
        } else null

        try {
            if (config != null) {
                val status = applyConfig(config, affectedUids = affectedUids)
                _state.update { it.copy(status = status) }
            }
            repository.replaceExcludedApps(replacement)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                if (config != null) {
                    val rollbackConfig = buildConfig(current.mode, excludedUids = previousUids)
                    runSuspendCatching { applyConfig(rollbackConfig, affectedUids = affectedUids) }
                        .onFailure {
                            _state.update { latest ->
                                latest.copy(
                                    uidPolicyStatus = latest.uidPolicyStatus.copy(
                                        known = false,
                                        inSync = false,
                                    ),
                                )
                            }
                        }
                }
                repository.replaceExcludedApps(previous)
                refreshRuntimeStatus()
                _state.update { latest ->
                    latest.copy(
                        excludedApps = previous,
                        installedApps = sortInstalledApps(
                            latest.installedApps.map { app ->
                                app.copy(excluded = app.uid in previousUids)
                            },
                        ),
                    )
                }
            }
            throw error
        }
        _state.update { latest ->
            latest.copy(
                excludedApps = replacement,
                installedApps = sortInstalledApps(
                    latest.installedApps.map { app ->
                        app.copy(excluded = app.uid in selectedUids)
                    },
                ),
                excludedAppDraftUids = null,
                message = if (wasRunning) {
                    "已应用 ${replacement.size} 个应用排除"
                } else {
                    "已保存 ${replacement.size} 个应用排除"
                },
            )
        }
    }

    private suspend fun buildConfig(
        mode: ProxyMode,
        nodeEntities: List<ProxyNodeEntity> = _state.value.nodes,
        excludedUids: Set<Int>? = null,
        traceContext: com.akiha.akihalink.performance.TraceContext? = null,
        hotspotProxyEnabled: Boolean? = null,
    ): String {
        val effectiveExcludedUids = excludedUids ?: resolveExcludedApps(
            repository.allExcludedApps(),
            installedAppCatalog.load(),
        ).effectiveUids
        return withContext(Dispatchers.Default) {
            PerformanceTrace.section("AKL/config_generate", traceContext) {
                val current = _state.value
                val selected = current.selectedNodeId ?: error("请先选择节点")
                val nodes = nodeEntities.map(repository::decryptNode)
                generator.generate(
                    ConfigRequest(
                        mode = mode,
                        nodes = nodes,
                        selectedNodeId = selected,
                        excludedUids = effectiveExcludedUids,
                        appUid = getApplication<Application>().applicationInfo.uid,
                        androidUserIds = androidUserIds(),
                        clashSecret = clashSecret,
                        runtimeOptions = AppConfigPolicy.runtimeOptions.copy(
                            reverseDnsMapping = current.reverseDnsMappingEnabled,
                        ),
                        hotspotProxyEnabled = hotspotProxyEnabled ?: current.hotspotProxyEnabled,
                    ),
                )
            }
        }
    }

    private fun buildUidPolicySummary(
        excludedUids: Set<Int>,
    ): UidPolicySummary = AndroidUidPolicy.build(
        androidUserIds = androidUserIds(),
        excludedUids = excludedUids,
        appUid = getApplication<Application>().applicationInfo.uid,
    ).summary

    private fun androidUserIds(): Set<Int> {
        val userManager = getApplication<Application>().getSystemService(UserManager::class.java)
        // UserHandle exposes the numeric handle through hashCode() in the public SDK.
        return userManager.userProfiles.mapTo(hashSetOf()) { it.hashCode() }.ifEmpty { setOf(0) }
    }

    private fun buildSpeedTestConfig(nodeEntities: List<ProxyNodeEntity>): String =
        speedTestGenerator.generate(
            SpeedTestConfigRequest(
                nodes = nodeEntities.map(repository::decryptNode),
                clashSecret = clashSecret,
                runtimeOptions = AppConfigPolicy.runtimeOptions,
            ),
        )

    private fun loadInstalledApps() {
        if (!shouldLoadInstalledApps(appForeground, appExclusionVisible) || installedAppsJob?.isActive == true) return
        val generation = ++installedAppsLoadGeneration
        _state.update {
            it.copy(
                installedAppsLoadState = InstalledAppsLoadState.LOADING,
                installedAppsLoadError = null,
            )
        }
        installedAppsJob = viewModelScope.launch {
            try {
                val installedPackages = installedAppCatalog.load()
                val storedExclusions = repository.allExcludedApps()
                val resolution = resolveExcludedApps(storedExclusions, installedPackages)
                val apps = groupInstalledApps(installedPackages, resolution.effectiveUids)
                if (installedAppsLoadGeneration != generation) return@launch
                _state.update { current ->
                    current.copy(
                        excludedApps = storedExclusions,
                        installedApps = sortInstalledApps(apps),
                        installedAppsLoadState = InstalledAppsLoadState.LOADED,
                        installedAppsLoadError = null,
                        uidPolicySummary = buildUidPolicySummary(resolution.effectiveUids),
                        message = if (resolution.stale.isNotEmpty()) {
                            "已停用 ${resolution.stale.size} 条无法验证身份的旧应用排除规则，请重新选择"
                        } else current.message,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (installedAppsLoadGeneration == generation) {
                    val detail = error.message?.take(200)?.takeIf(String::isNotBlank)
                    _state.update {
                        it.copy(
                            installedAppsLoadState = InstalledAppsLoadState.FAILED,
                            installedAppsLoadError = detail ?: "无法读取已安装应用，请重试",
                        )
                    }
                }
            } finally {
                if (installedAppsLoadGeneration == generation) installedAppsJob = null
            }
        }
    }

    private suspend fun refreshRuntimeHealth() {
        val cached = _state.value.status
        runSuspendCatching { controller.statusHealth() }
            .onSuccess { status ->
                val verifiedStatus = if (status.actualState == "running" && status.controllerReady == true) {
                    runSuspendCatching {
                        clashApi.awaitReady(clashSecret, MAIN_CLASH_PORT)
                    }.fold(
                        onSuccess = { status },
                        onFailure = { status.copy(controllerReady = false) },
                    )
                } else {
                    status
                }
                _state.update { current ->
                    if (!current.status.sameRuntimeInstance(cached)) {
                        current
                    } else {
                        current.copy(
                            status = verifiedStatus,
                            runtimeStale = false,
                            runtimeRefreshError = null,
                        )
                    }
                }
                syncFailedHotspotSetting(verifiedStatus)
                saveStartupRuntimeSnapshot()
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        runtimeStale = true,
                        runtimeRefreshError = error.message?.take(500) ?: "Runtime health status needs confirmation",
                    )
                }
            }
    }

    private suspend fun applyConfig(
        config: String,
        affectedUids: Set<Int>? = null,
    ): ModuleStatus {
        val exclusionResult = affectedUids?.let { controller.applyExclusions(config, it) }
        val status = exclusionResult?.runningStatus ?: controller.apply(config)
        if (status.actualState == "running" && exclusionResult != null &&
            (!exclusionResult.policyStatus.known || !exclusionResult.policyStatus.inSync)
        ) {
            error("UID policy did not reach a verified kernel state")
        }
        _state.update {
            it.copy(
                status = status,
                uidPolicyStatus = exclusionResult?.policyStatus ?: it.uidPolicyStatus,
                runtimeStale = false,
                runtimeRefreshError = null,
                message = status.dnsCacheRecoveryMessage() ?: it.message,
            )
        }
        if (status.actualState == "running") {
            syncTelemetryTarget()
        }
        saveStartupRuntimeSnapshot()
        return status
    }

    private suspend fun activateCurrentConfig(
        mode: ProxyMode,
        hotspotProxyEnabled: Boolean,
        trace: com.akiha.akihalink.performance.TraceContext,
    ) = PerformanceTrace.suspendSection("AKL/root_activate", trace) {
        controller.activate(
            buildConfig(mode, traceContext = trace, hotspotProxyEnabled = hotspotProxyEnabled),
            trace.id,
        )
    }

    private suspend fun disableHotspotSetting() {
        runSuspendCatching { repository.setHotspotProxyEnabled(false) }
        _state.update { it.copy(hotspotProxyEnabled = false, message = null) }
    }

    private suspend fun syncFailedHotspotSetting(status: ModuleStatus) {
        if (_state.value.hotspotProxyEnabled && status.hotspotProxyState == "failed") {
            disableHotspotSetting()
        }
    }

    private suspend fun retryPendingPackageReconciliation(): ModuleStatus? {
        if (PackageReconciliationStore(getApplication()).load().isEmpty) return null
        return runSuspendCatching { drainPendingPackageChanges(getApplication()) }.fold(
            onSuccess = { it.status },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        message = error.message?.take(300)
                            ?.let { detail -> "应用排除规则同步待重试：$detail" }
                            ?: "应用排除规则将在下次启动时重试",
                    )
                }
                null
            },
        )
    }

    private fun startHotspotStatePolling() {
        hotspotStatePollJob?.cancel()
        hotspotStatePollJob = viewModelScope.launch {
            persistenceReady.await()
            while (appForeground && isActive) {
                delay(HOTSPOT_STATE_POLL_MILLIS)
                val current = _state.value
                if (!current.hotspotProxyEnabled || !current.status.proxyMayBeActive() || current.busy) continue
                if (!actionMutex.tryLock()) continue
                try {
                    val status = runSuspendCatching { controller.statusFast() }.getOrNull() ?: continue
                    _state.update { it.copy(status = status.retainControllerReadiness(it.status)) }
                    if (status.hotspotProxyState == "failed") {
                        disableHotspotSetting()
                        runSuspendCatching {
                            applyConfig(buildConfig(current.mode, hotspotProxyEnabled = false))
                        }
                        _state.update { it.copy(message = null) }
                    }
                } finally {
                    actionMutex.unlock()
                }
            }
        }
    }

    private suspend fun syncTelemetryTarget() {
        val current = _state.value
        val entity = current.nodes.firstOrNull { it.id == current.selectedNodeId } ?: return
        runSuspendCatching {
            val node = repository.decryptNode(entity)
            controller.updateTelemetryTarget(
                SingBoxConfigGenerator.nodeTag(node),
                node.fingerprint,
            )
        }
    }


    private suspend fun refreshRuntimeStatus() {
        refreshRuntimeStatusFast(showFailure = false)
    }

    private suspend fun ensureRuntimeStateFreshForMutation() {
        val before = _state.value
        if (!runtimeStateNeedsConfirmation(before)) return
        var compatibility = before.compatibility
        if (compatibility == null || before.runtimeStale || before.runtimeRefreshError != null) {
            compatibility = controller.compatibility()
            if (compatibility is ModuleCompatibility.Error) {
                throw IllegalStateException(compatibility.message)
            }
            _state.update {
                it.copy(
                    compatibility = compatibility,
                    probe = if (compatibility == ModuleCompatibility.Compatible) it.probe else null,
                )
            }
        }
        if (compatibility == ModuleCompatibility.Compatible) {
            refreshRuntimeStatusFast(showFailure = true)
            if (_state.value.runtimeStale || _state.value.runtimeRefreshError != null) {
                throw IllegalStateException("无法确认最新运行状态，请重试")
            }
        } else {
            _state.update {
                it.copy(
                    status = ModuleStatus(),
                    runtimeStale = false,
                    runtimeRefreshError = null,
                )
            }
        }
    }

    private suspend fun refreshRuntimeStatusFast(showFailure: Boolean): ModuleStatus {
        val cached = _state.value.status
        return runSuspendCatching { controller.statusFast() }.fold(
            onSuccess = { status ->
                var mergedStatus = status
                _state.update { current ->
                    mergedStatus = status.retainControllerReadiness(current.status)
                    current.copy(
                        status = mergedStatus,
                        runtimeStale = false,
                        runtimeRefreshError = null,
                        message = mergedStatus.dnsCacheRecoveryMessage() ?: current.message,
                    )
                }
                saveStartupRuntimeSnapshot()
                mergedStatus
            },
            onFailure = { error ->
                val message = error.message?.take(500) ?: "运行状态待确认"
                _state.update {
                    it.copy(
                        runtimeStale = true,
                        runtimeRefreshError = message,
                        message = if (showFailure) "状态待确认：$message" else it.message,
                    )
                }
                cached
            },
        )
    }

    private fun saveStartupRuntimeSnapshot() {
        startupRuntimeCache.save(_state.value)
    }

    private suspend fun cancelSpeedTestForMutation() {
        speedTestJob?.cancelAndJoin()
    }

    private fun cancelNodeDnsValidation() {
        dnsValidationGeneration++
        dnsValidationJob?.cancel()
        dnsValidationJob = null
        _state.update {
            it.copy(
                dnsValidation = DnsValidationState.IDLE,
                dnsValidationNodeId = null,
                dnsValidationGeneration = dnsValidationGeneration,
            )
        }
    }

    private fun startNodeDnsValidation(nodeId: String, nodeTag: String, generation: Long) {
        dnsValidationJob = viewModelScope.launch {
            val validation = try {
                if (
                    clashApi.delay(
                        secret = clashSecret,
                        port = MAIN_CLASH_PORT,
                        nodeTag = nodeTag,
                        timeoutMillis = NODE_DNS_VALIDATION_TIMEOUT_MILLIS,
                    ) != null
                ) {
                    DnsValidationState.HEALTHY
                } else {
                    DnsValidationState.DEGRADED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                refreshRuntimeHealth()
                DnsValidationState.DEGRADED
            }
            _state.update { current ->
                val updated = applyNodeDnsValidation(current, nodeId, generation, validation)
                if (updated === current || validation != DnsValidationState.DEGRADED) {
                    updated
                } else {
                    updated.copy(message = "当前节点 DNS 验证失败")
                }
            }
        }
    }

    private suspend fun pruneLatencyResults() {
        val validNodes = repository.allStoredNodes()
        val validNodeIds = validNodes.mapTo(hashSetOf()) { it.id }
        repository.pruneNodeLatencyResults(validNodeIds)
        _state.update { current ->
            current.copy(
                nodeLatencyResults = current.nodeLatencyResults.filterKeys { it in validNodeIds },
            )
        }
    }

    private fun launchBusy(
        refreshPolicy: RuntimeRefreshPolicy = RuntimeRefreshPolicy.REQUIRE_FRESH_IF_STALE,
        traceName: String? = null,
        traceContext: com.akiha.akihalink.performance.TraceContext? = null,
        block: suspend () -> Unit,
    ) {
        if (_state.value.busy) return
        val operationGeneration = runtimeCoordinator.issue()
        val operationTrace = traceContext ?: traceName?.let { PerformanceTrace.create() }
        if (operationTrace != null && traceName != null) {
            PerformanceTrace.beginAsync(traceName, operationTrace)
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var actionLockHeld = false
            try {
                if (speedTestJob?.isActive == true) cancelSpeedTestForMutation()
                persistenceReady.await()
                if (!runtimeCoordinator.isCurrent(operationGeneration)) return@launch
                environmentRefreshRunner.cancelAndJoin()
                _state.update { it.copy(runtimeRefreshing = false) }
                actionMutex.lock()
                actionLockHeld = true
                if (!runtimeCoordinator.isCurrent(operationGeneration)) return@launch
                _state.update { it.copy(busy = true, runtimeRefreshing = false, message = null) }
                when (refreshPolicy) {
                    RuntimeRefreshPolicy.NONE -> Unit
                    RuntimeRefreshPolicy.REQUIRE_FRESH_IF_STALE -> ensureRuntimeStateFreshForMutation()
                }
                runtimeCoordinator.run(operationGeneration) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is ActivationException) {
                    _state.update {
                        it.copy(
                            status = error.activation.status,
                            message = error.message?.take(500) ?: "代理启动失败，状态已回滚",
                        )
                    }
                    saveStartupRuntimeSnapshot()
                } else {
                    _state.update { it.copy(message = error.message?.take(500) ?: "操作失败") }
                }
            } finally {
                _state.update { it.copy(busy = false) }
                if (actionLockHeld) actionMutex.unlock()
                if (operationTrace != null && traceName != null) {
                    PerformanceTrace.endAsync(traceName, operationTrace)
                }
            }
        }
    }

    private companion object {
        const val MAIN_CLASH_PORT = 9090
        const val NODE_DNS_VALIDATION_TIMEOUT_MILLIS = 5_000
        const val HOTSPOT_STATE_POLL_MILLIS = 5_000L
    }
}
