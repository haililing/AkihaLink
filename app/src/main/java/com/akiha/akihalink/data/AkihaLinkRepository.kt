package com.akiha.akihalink.data

import com.akiha.akihalink.security.SecretCipher
import com.akiha.akihalink.speedtest.NodeLatencyResult
import com.akiha.akihalink.subscription.ProxyNode
import com.akiha.akihalink.subscription.SubscriptionHttpClient
import com.akiha.akihalink.subscription.SubscriptionParser
import com.akiha.akihalink.subscription.SubscriptionParseException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal fun shouldClearSubscriptionSelection(
    enabled: Boolean,
    selectedNodeId: String?,
    subscriptionNodeIds: Set<String>,
): Boolean = !enabled && selectedNodeId != null && selectedNodeId in subscriptionNodeIds

internal fun persistedHotspotProxyEnabled(value: String?): Boolean =
    value?.toBooleanStrictOrNull() ?: false

sealed interface SubscriptionUpdateResult {
    data class Updated(
        val selectionStillValid: Boolean,
        val previousSubscription: SubscriptionEntity,
        val previousNodes: List<ProxyNodeEntity>,
        val previousSelectedNodeId: String?,
    ) : SubscriptionUpdateResult

    data class Failed(val message: String) : SubscriptionUpdateResult
}

data class SubscriptionDeletion(
    val subscription: SubscriptionEntity,
    val nodes: List<ProxyNodeEntity>,
    val selectedNodeId: String?,
) {
    val selectedRemoved: Boolean
        get() = selectedNodeId?.startsWith("${subscription.id}:") == true
}

data class SubscriptionEnabledChange(
    val subscription: SubscriptionEntity,
    val enabled: Boolean,
    val selectedNodeId: String?,
    val selectionCleared: Boolean,
)

class AkihaLinkRepository(
    private val dao: AkihaLinkDao,
    private val cipher: SecretCipher,
    private val httpClient: SubscriptionHttpClient = SubscriptionHttpClient(),
    private val parser: SubscriptionParser = SubscriptionParser(),
) {
    val subscriptions: Flow<List<SubscriptionEntity>> = dao.observeSubscriptions()
    val nodes: Flow<List<ProxyNodeEntity>> = dao.observeNodes()
    val subscriptionNodeCounts: Flow<List<SubscriptionNodeCount>> = dao.observeSubscriptionNodeCounts()
    val excludedApps: Flow<List<ExcludedAppEntity>> = dao.observeExcludedApps()

    suspend fun addSubscription(name: String, url: String): SubscriptionEntity {
        require(name.isNotBlank()) { "Subscription name is required" }
        val id = UUID.randomUUID().toString()
        val downloaded = httpClient.download(url.trim())
        val parsed = parser.parse(downloaded.content, id)
        val now = System.currentTimeMillis()
        val subscription = SubscriptionEntity(
            id = id,
            name = name.trim().take(80),
            encryptedUrl = cipher.encrypt(url.trim()),
            lastUpdatedAt = now,
            lastError = parsed.warningMessage(),
            createdAt = now,
            trafficUploadBytes = downloaded.userInfo?.uploadBytes,
            trafficDownloadBytes = downloaded.userInfo?.downloadBytes,
            trafficTotalBytes = downloaded.userInfo?.totalBytes,
            expiresAt = downloaded.userInfo?.expiresAt,
            refreshIntervalHours = downloaded.refreshIntervalHours,
            subscriptionStartedAt = downloaded.userInfo?.startedAt,
        )
        dao.replaceSubscription(subscription, parsed.nodes.map(::encryptNode))
        return subscription
    }

    suspend fun updateSubscription(subscription: SubscriptionEntity): SubscriptionUpdateResult {
        val previousNodes = dao.getNodesForSubscription(subscription.id)
        return try {
            val downloaded = httpClient.download(cipher.decrypt(subscription.encryptedUrl))
            val parsed = parser.parse(downloaded.content, subscription.id)
            val selectedNodeId = dao.getSetting(SETTING_SELECTED_NODE)
            val selectedRemoved = selectedNodeId?.startsWith("${subscription.id}:") == true &&
                parsed.nodes.none { it.id == selectedNodeId }
            dao.replaceSubscription(
                subscription.copy(
                    lastUpdatedAt = System.currentTimeMillis(),
                    lastError = if (selectedRemoved) {
                        "当前节点已从订阅中移除，请重新选择"
                    } else {
                        parsed.warningMessage()
                    },
                    trafficUploadBytes = downloaded.userInfo?.uploadBytes ?: subscription.trafficUploadBytes,
                    trafficDownloadBytes = downloaded.userInfo?.downloadBytes ?: subscription.trafficDownloadBytes,
                    trafficTotalBytes = downloaded.userInfo?.totalBytes ?: subscription.trafficTotalBytes,
                    expiresAt = downloaded.userInfo
                        ?.takeIf { it.expirationProvided }
                        ?.expiresAt
                        ?: subscription.expiresAt.takeUnless {
                            downloaded.userInfo?.expirationProvided == true
                        },
                    refreshIntervalHours = downloaded.refreshIntervalHours
                        ?: subscription.refreshIntervalHours,
                    subscriptionStartedAt = downloaded.userInfo?.startedAt
                        ?: subscription.subscriptionStartedAt,
                ),
                parsed.nodes.map(::encryptNode),
                clearSelection = selectedRemoved,
            )
            SubscriptionUpdateResult.Updated(
                selectionStillValid = !selectedRemoved,
                previousSubscription = subscription,
                previousNodes = previousNodes,
                previousSelectedNodeId = selectedNodeId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = safeError(error)
            dao.upsertSubscription(subscription.copy(lastError = message))
            SubscriptionUpdateResult.Failed(message)
        }
    }

    suspend fun restoreSubscription(update: SubscriptionUpdateResult.Updated, reason: String) {
        dao.restoreSubscription(
            update.previousSubscription.copy(lastError = reason.take(200)),
            update.previousNodes,
            update.previousSelectedNodeId,
        )
    }

    suspend fun setSubscriptionEnabled(
        subscription: SubscriptionEntity,
        enabled: Boolean,
    ): SubscriptionEnabledChange {
        val selectedNodeId = selectedNodeId()
        val selectionCleared = shouldClearSubscriptionSelection(
            enabled = enabled,
            selectedNodeId = selectedNodeId,
            subscriptionNodeIds = dao.getNodesForSubscription(subscription.id).mapTo(hashSetOf()) { it.id },
        )
        val change = SubscriptionEnabledChange(
            subscription = subscription,
            enabled = enabled,
            selectedNodeId = selectedNodeId,
            selectionCleared = selectionCleared,
        )
        dao.setSubscriptionEnabledWithSelection(
            subscriptionId = subscription.id,
            enabled = enabled,
            clearSelection = change.selectionCleared,
        )
        return change
    }

    suspend fun restoreSubscriptionEnabled(change: SubscriptionEnabledChange) {
        dao.restoreSubscriptionEnabled(
            subscriptionId = change.subscription.id,
            enabled = change.subscription.isEnabled,
            selectedNodeId = change.selectedNodeId.takeIf { change.selectionCleared },
        )
    }

    suspend fun selectNode(nodeId: String) = dao.putSetting(AppSettingEntity(SETTING_SELECTED_NODE, nodeId))
    suspend fun selectedNodeId(): String? = dao.getSetting(SETTING_SELECTED_NODE)
    suspend fun clearSelectedNode() = dao.deleteSetting(SETTING_SELECTED_NODE)
    suspend fun setMode(mode: String) = dao.putSetting(AppSettingEntity(SETTING_MODE, mode))
    suspend fun mode(): String = dao.getSetting(SETTING_MODE) ?: "rule"
    suspend fun setReverseDnsMappingEnabled(enabled: Boolean) =
        dao.putSetting(AppSettingEntity(SETTING_REVERSE_DNS_MAPPING, enabled.toString()))
    suspend fun reverseDnsMappingEnabled(): Boolean =
        dao.getSetting(SETTING_REVERSE_DNS_MAPPING)?.toBooleanStrictOrNull() ?: true
    suspend fun setHotspotProxyEnabled(enabled: Boolean) =
        dao.putSetting(AppSettingEntity(SETTING_HOTSPOT_PROXY_ENABLED, enabled.toString()))
    suspend fun hotspotProxyEnabled(): Boolean =
        persistedHotspotProxyEnabled(dao.getSetting(SETTING_HOTSPOT_PROXY_ENABLED))
    suspend fun setNodeSort(sort: NodeSort) =
        dao.putSetting(AppSettingEntity(SETTING_NODE_SORT, sort.wireName))
    suspend fun nodeSort(): NodeSort = NodeSort.fromWireName(dao.getSetting(SETTING_NODE_SORT))
    suspend fun nodeLatencyResults(): Map<String, NodeLatencyResult> =
        dao.getSettingsByPrefix(SETTING_NODE_LATENCY_PREFIX).mapNotNull { setting ->
            val nodeId = setting.key.removePrefix(SETTING_NODE_LATENCY_PREFIX)
            runCatching { nodeId to JSON.decodeFromString<NodeLatencyResult>(setting.value) }.getOrNull()
        }.toMap()
    suspend fun saveNodeLatencyResults(results: Map<String, NodeLatencyResult>) =
        dao.putSettings(
            results.map { (nodeId, result) ->
                AppSettingEntity(
                    SETTING_NODE_LATENCY_PREFIX + nodeId,
                    JSON.encodeToString(result),
                )
            },
        )
    suspend fun pruneNodeLatencyResults(validNodeIds: Set<String>) {
        val staleKeys = dao.getSettingsByPrefix(SETTING_NODE_LATENCY_PREFIX)
            .filter { it.key.removePrefix(SETTING_NODE_LATENCY_PREFIX) !in validNodeIds }
            .map { it.key }
        if (staleKeys.isNotEmpty()) dao.deleteSettings(staleKeys)
    }
    suspend fun cleanupLegacyPerformanceSettings() {
        migrateRemovedPerformanceSettings(dao)
    }

    companion object {
        const val SETTING_SELECTED_NODE = "selected_node"
        const val SETTING_MODE = "mode"
        const val SETTING_REVERSE_DNS_MAPPING = "reverse_dns_mapping"
        const val SETTING_HOTSPOT_PROXY_ENABLED = "hotspot_proxy_enabled"
        const val SETTING_TCP_SOCKET_BBR = "tcp_socket_bbr"
        const val SETTING_FAILOVER_ENABLED = "failover_enabled"
        const val SETTING_NODE_SORT = "node_sort"
        const val SETTING_NODE_LATENCY_PREFIX = "node_latency:"
        const val SETTING_NODE_TUNING_PREFIX = "node_tuning:"
        private const val REMOVED_LIGHT_DIAGNOSTIC_PREFIX = "light_diagnostic:"
        const val SETTING_PROXY_SCOPE = "proxy_scope"
        const val SETTING_INCLUDED_UIDS = "included_uids"
        const val SETTING_NETWORK_ALIAS_PREFIX = "network_alias:"
        const val SETTING_NODE_HISTORY_PREFIX = "node_history:"
        private const val REMOVED_HTTP3_POLICY_SETTING = "http3_policy"
        private const val REMOVED_NODE_SORT_QUALITY = "quality"
        private const val LEGACY_NETWORK_QUALITY_PREFIX = "network_quality:"
        private val JSON = Json { ignoreUnknownKeys = true }

        internal suspend fun migrateRemovedPerformanceSettings(dao: AkihaLinkDao) {
            if (dao.getSetting(SETTING_NODE_SORT) == REMOVED_NODE_SORT_QUALITY) {
                dao.putSetting(AppSettingEntity(SETTING_NODE_SORT, NodeSort.NAME.wireName))
            }
            val legacyKeys = buildList {
                add(SETTING_TCP_SOCKET_BBR)
                add(SETTING_FAILOVER_ENABLED)
                add(SETTING_PROXY_SCOPE)
                add(SETTING_INCLUDED_UIDS)
                add(REMOVED_HTTP3_POLICY_SETTING)
                addAll(dao.getSettingsByPrefix(LEGACY_NETWORK_QUALITY_PREFIX).map { it.key })
                addAll(dao.getSettingsByPrefix(SETTING_NETWORK_ALIAS_PREFIX).map { it.key })
                addAll(dao.getSettingsByPrefix(SETTING_NODE_HISTORY_PREFIX).map { it.key })
                addAll(dao.getSettingsByPrefix(SETTING_NODE_TUNING_PREFIX).map { it.key })
                addAll(dao.getSettingsByPrefix(REMOVED_LIGHT_DIAGNOSTIC_PREFIX).map { it.key })
            }
            dao.deleteSettings(legacyKeys.distinct())
        }
    }

    suspend fun allNodes(): List<ProxyNodeEntity> = dao.getNodes()
    suspend fun allStoredNodes(): List<ProxyNodeEntity> = dao.getAllNodes()
    suspend fun allExcludedApps(): List<ExcludedAppEntity> = dao.getExcludedApps()

    suspend fun deleteSubscription(subscription: SubscriptionEntity): SubscriptionDeletion {
        val selected = selectedNodeId()
        val deletion = SubscriptionDeletion(
            subscription = subscription,
            nodes = dao.getNodesForSubscription(subscription.id),
            selectedNodeId = selected,
        )
        dao.deleteSubscriptionWithSelection(subscription.id, deletion.selectedRemoved)
        return deletion
    }

    suspend fun restoreSubscription(deletion: SubscriptionDeletion) {
        dao.restoreSubscription(
            deletion.subscription,
            deletion.nodes,
            deletion.selectedNodeId.takeIf { deletion.selectedRemoved },
        )
    }

    suspend fun replaceExcludedApps(apps: List<ExcludedAppEntity>) = dao.replaceExcludedApps(apps)

    fun decryptNode(entity: ProxyNodeEntity): ProxyNode = ProxyNode(
        id = entity.id,
        subscriptionId = entity.subscriptionId,
        fingerprint = entity.fingerprint,
        name = entity.name,
        protocol = entity.protocol,
        endpoint = entity.endpoint,
        outbound = Json.parseToJsonElement(cipher.decrypt(entity.encryptedOutbound)).jsonObject,
    )

    private fun encryptNode(node: ProxyNode): ProxyNodeEntity = ProxyNodeEntity(
        id = node.id,
        subscriptionId = node.subscriptionId,
        fingerprint = node.fingerprint,
        name = node.name,
        protocol = node.protocol,
        endpoint = node.endpoint,
        encryptedOutbound = cipher.encrypt(node.outbound.toString()),
    )

    private fun safeError(error: Throwable): String = when (error) {
        is SubscriptionParseException -> error.message.orEmpty().take(200)
        else -> "订阅更新失败"
    }

}

enum class NodeSort(val wireName: String) {
    LATENCY("latency"),
    NAME("name");

    companion object {
        fun fromWireName(value: String?): NodeSort =
            entries.firstOrNull { it.wireName == value } ?: NAME
    }
}
