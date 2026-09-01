package com.akiha.akihalink.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AkihaLinkDaoSelectionTest {
    @Test
    fun hotspotProxyDefaultsOffAndRestoresStrictBoolean() {
        assertFalse(persistedHotspotProxyEnabled(null))
        assertFalse(persistedHotspotProxyEnabled("TRUE"))
        assertTrue(persistedHotspotProxyEnabled("true"))
    }

    @Test
    fun removedQualityAndHttp3SettingsAreMigratedWithoutTouchingLatencyOrSelection() = runBlocking {
        val dao = RecordingDao()
        dao.putSettings(
            listOf(
                AppSettingEntity(AkihaLinkRepository.SETTING_NODE_SORT, "quality"),
                AppSettingEntity("http3_policy", "fallback"),
                AppSettingEntity("network_quality:wlan0", "legacy"),
                AppSettingEntity(AkihaLinkRepository.SETTING_NODE_LATENCY_PREFIX + "node", "saved-latency"),
                AppSettingEntity(AkihaLinkRepository.SETTING_SELECTED_NODE, "node"),
            ),
        )

        AkihaLinkRepository.migrateRemovedPerformanceSettings(dao)

        assertEquals("name", dao.getSetting(AkihaLinkRepository.SETTING_NODE_SORT))
        assertNull(dao.getSetting("http3_policy"))
        assertNull(dao.getSetting("network_quality:wlan0"))
        assertEquals("saved-latency", dao.getSetting(AkihaLinkRepository.SETTING_NODE_LATENCY_PREFIX + "node"))
        assertEquals("node", dao.getSetting(AkihaLinkRepository.SETTING_SELECTED_NODE))
    }

    @Test
    fun replacementClearsRemovedSelectionAndRestorePutsItBack() = runBlocking {
        val dao = RecordingDao()
        val subscription = SubscriptionEntity("sub", "Subscription", "encrypted")
        val selectedNode = node("sub:old")
        dao.putSetting(AppSettingEntity(AkihaLinkRepository.SETTING_SELECTED_NODE, selectedNode.id))

        dao.replaceSubscription(subscription, listOf(node("sub:new")), clearSelection = true)

        assertNull(dao.getSetting(AkihaLinkRepository.SETTING_SELECTED_NODE))
        assertEquals(listOf("sub:new"), dao.getNodesForSubscription("sub").map { it.id })

        dao.restoreSubscription(subscription, listOf(selectedNode), selectedNode.id)

        assertEquals(selectedNode.id, dao.getSetting(AkihaLinkRepository.SETTING_SELECTED_NODE))
        assertEquals(listOf(selectedNode.id), dao.getNodesForSubscription("sub").map { it.id })
    }

    @Test
    fun disablingSubscriptionHidesNodesAndClearsItsSelection() = runBlocking {
        val dao = RecordingDao()
        val subscription = SubscriptionEntity("sub", "Subscription", "encrypted")
        val selectedNode = node("sub:selected")
        dao.replaceSubscription(subscription, listOf(selectedNode))
        dao.putSetting(AppSettingEntity(AkihaLinkRepository.SETTING_SELECTED_NODE, selectedNode.id))

        dao.setSubscriptionEnabledWithSelection("sub", enabled = false, clearSelection = true)

        assertFalse(dao.subscription("sub").isEnabled)
        assertEquals(emptyList<String>(), dao.getNodes().map { it.id })
        assertEquals(listOf(selectedNode.id), dao.getAllNodes().map { it.id })
        assertNull(dao.getSetting(AkihaLinkRepository.SETTING_SELECTED_NODE))
    }

    @Test
    fun restoringSubscriptionStateRestoresSelection() = runBlocking {
        val dao = RecordingDao()
        val subscription = SubscriptionEntity("sub", "Subscription", "encrypted")
        val selectedNode = node("sub:selected")
        dao.replaceSubscription(subscription, listOf(selectedNode))

        dao.setSubscriptionEnabledWithSelection("sub", enabled = false, clearSelection = true)
        dao.restoreSubscriptionEnabled("sub", enabled = true, selectedNodeId = selectedNode.id)

        assertTrue(dao.subscription("sub").isEnabled)
        assertEquals(listOf(selectedNode.id), dao.getNodes().map { it.id })
        assertEquals(selectedNode.id, dao.getSetting(AkihaLinkRepository.SETTING_SELECTED_NODE))
    }

    @Test
    fun selectionClearDecisionUsesStoredNodeMembershipInsteadOfIdPrefix() {
        val subscriptionNodes = setOf("arbitrary-node-id")

        assertTrue(shouldClearSubscriptionSelection(false, "arbitrary-node-id", subscriptionNodes))
        assertFalse(shouldClearSubscriptionSelection(false, "other-node", subscriptionNodes))
        assertFalse(shouldClearSubscriptionSelection(true, "arbitrary-node-id", subscriptionNodes))
    }

    private fun node(id: String) = ProxyNodeEntity(
        id = id,
        subscriptionId = "sub",
        fingerprint = id.substringAfter(':').padEnd(64, '0'),
        name = id,
        protocol = "trojan",
        endpoint = "example.com:443",
        encryptedOutbound = "encrypted",
    )

    private class RecordingDao : AkihaLinkDao {
        private val subscriptions = linkedMapOf<String, SubscriptionEntity>()
        private val nodes = linkedMapOf<String, ProxyNodeEntity>()
        private val settings = linkedMapOf<String, AppSettingEntity>()
        private val excludedApps = linkedMapOf<Pair<Int, String>, ExcludedAppEntity>()

        override fun observeSubscriptions(): Flow<List<SubscriptionEntity>> = flowOf(subscriptions.values.toList())
        override fun observeNodes(): Flow<List<ProxyNodeEntity>> = flowOf(enabledNodes())
        override suspend fun getNodes(): List<ProxyNodeEntity> = enabledNodes()
        override suspend fun getAllNodes(): List<ProxyNodeEntity> = nodes.values.toList()
        override fun observeSubscriptionNodeCounts(): Flow<List<SubscriptionNodeCount>> = flowOf(
            nodes.values.groupingBy { it.subscriptionId }.eachCount().map { (subscriptionId, count) ->
                SubscriptionNodeCount(subscriptionId, count)
            },
        )
        override suspend fun getNodesForSubscription(subscriptionId: String): List<ProxyNodeEntity> =
            nodes.values.filter { it.subscriptionId == subscriptionId }
        override fun observeExcludedApps(): Flow<List<ExcludedAppEntity>> = flowOf(excludedApps.values.toList())
        override suspend fun getExcludedApps(): List<ExcludedAppEntity> = excludedApps.values.toList()
        override suspend fun upsertSubscription(subscription: SubscriptionEntity) {
            subscriptions[subscription.id] = subscription
        }
        override suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) {
            subscriptions[subscriptionId]?.let { subscription ->
                subscriptions[subscriptionId] = subscription.copy(isEnabled = enabled)
            }
        }
        override suspend fun upsertNodes(nodes: List<ProxyNodeEntity>) {
            nodes.forEach { this.nodes[it.id] = it }
        }
        override suspend fun deleteNodesForSubscription(subscriptionId: String) {
            nodes.entries.removeAll { it.value.subscriptionId == subscriptionId }
        }
        override suspend fun deleteSubscription(id: String) {
            subscriptions.remove(id)
            deleteNodesForSubscription(id)
        }
        override suspend fun upsertExcludedApps(apps: List<ExcludedAppEntity>) {
            apps.forEach { excludedApps[it.userId to it.packageName] = it }
        }
        override suspend fun clearExcludedApps() {
            excludedApps.clear()
        }
        override suspend fun putSetting(setting: AppSettingEntity) {
            settings[setting.key] = setting
        }
        override suspend fun putSettings(settings: List<AppSettingEntity>) {
            settings.forEach { putSetting(it) }
        }
        override suspend fun getSetting(key: String): String? = settings[key]?.value
        override suspend fun getSettingsByPrefix(prefix: String): List<AppSettingEntity> =
            settings.values.filter { it.key.startsWith(prefix) }
        override suspend fun deleteSetting(key: String) {
            settings.remove(key)
        }
        override suspend fun deleteSettings(keys: List<String>) {
            keys.forEach(settings::remove)
        }

        fun subscription(id: String): SubscriptionEntity = checkNotNull(subscriptions[id])

        private fun enabledNodes(): List<ProxyNodeEntity> = nodes.values.filter { node ->
            subscriptions[node.subscriptionId]?.isEnabled == true
        }
    }
}
