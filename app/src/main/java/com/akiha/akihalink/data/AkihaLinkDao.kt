package com.akiha.akihalink.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AkihaLinkDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query(
        """
        SELECT proxy_nodes.* FROM proxy_nodes
        INNER JOIN subscriptions ON subscriptions.id = proxy_nodes.subscriptionId
        WHERE subscriptions.isEnabled = 1
        ORDER BY proxy_nodes.name COLLATE NOCASE
        """,
    )
    fun observeNodes(): Flow<List<ProxyNodeEntity>>

    @Query(
        """
        SELECT proxy_nodes.* FROM proxy_nodes
        INNER JOIN subscriptions ON subscriptions.id = proxy_nodes.subscriptionId
        WHERE subscriptions.isEnabled = 1
        ORDER BY proxy_nodes.name COLLATE NOCASE
        """,
    )
    suspend fun getNodes(): List<ProxyNodeEntity>

    @Query("SELECT * FROM proxy_nodes ORDER BY name COLLATE NOCASE")
    suspend fun getAllNodes(): List<ProxyNodeEntity>

    @Query(
        """
        SELECT subscriptionId, COUNT(*) AS nodeCount
        FROM proxy_nodes
        GROUP BY subscriptionId
        """,
    )
    fun observeSubscriptionNodeCounts(): Flow<List<SubscriptionNodeCount>>

    @Query("SELECT * FROM proxy_nodes WHERE subscriptionId = :subscriptionId ORDER BY name COLLATE NOCASE")
    suspend fun getNodesForSubscription(subscriptionId: String): List<ProxyNodeEntity>

    @Query("SELECT * FROM excluded_apps ORDER BY label COLLATE NOCASE")
    fun observeExcludedApps(): Flow<List<ExcludedAppEntity>>

    @Query("SELECT * FROM excluded_apps ORDER BY label COLLATE NOCASE")
    suspend fun getExcludedApps(): List<ExcludedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubscription(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET isEnabled = :enabled WHERE id = :subscriptionId")
    suspend fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<ProxyNodeEntity>)

    @Query("DELETE FROM proxy_nodes WHERE subscriptionId = :subscriptionId")
    suspend fun deleteNodesForSubscription(subscriptionId: String)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExcludedApps(apps: List<ExcludedAppEntity>)

    @Query("DELETE FROM excluded_apps")
    suspend fun clearExcludedApps()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: AppSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSettings(settings: List<AppSettingEntity>)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?

    @Query("SELECT * FROM settings WHERE `key` LIKE :prefix || '%'")
    suspend fun getSettingsByPrefix(prefix: String): List<AppSettingEntity>

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    @Query("DELETE FROM settings WHERE `key` IN (:keys)")
    suspend fun deleteSettings(keys: List<String>)

    @Transaction
    suspend fun replaceSubscriptionNodes(subscriptionId: String, nodes: List<ProxyNodeEntity>) {
        deleteNodesForSubscription(subscriptionId)
        upsertNodes(nodes)
    }

    @Transaction
    suspend fun replaceSubscription(
        subscription: SubscriptionEntity,
        nodes: List<ProxyNodeEntity>,
        clearSelection: Boolean = false,
    ) {
        upsertSubscription(subscription)
        replaceSubscriptionNodes(subscription.id, nodes)
        if (clearSelection) deleteSetting(AkihaLinkRepository.SETTING_SELECTED_NODE)
    }

    @Transaction
    suspend fun deleteSubscriptionWithSelection(subscriptionId: String, clearSelection: Boolean) {
        deleteSubscription(subscriptionId)
        if (clearSelection) deleteSetting(AkihaLinkRepository.SETTING_SELECTED_NODE)
    }

    @Transaction
    suspend fun restoreSubscription(
        subscription: SubscriptionEntity,
        nodes: List<ProxyNodeEntity>,
        selectedNodeId: String?,
    ) {
        replaceSubscription(subscription, nodes)
        if (selectedNodeId != null) {
            putSetting(AppSettingEntity(AkihaLinkRepository.SETTING_SELECTED_NODE, selectedNodeId))
        }
    }

    @Transaction
    suspend fun setSubscriptionEnabledWithSelection(
        subscriptionId: String,
        enabled: Boolean,
        clearSelection: Boolean,
    ) {
        setSubscriptionEnabled(subscriptionId, enabled)
        if (clearSelection) deleteSetting(AkihaLinkRepository.SETTING_SELECTED_NODE)
    }

    @Transaction
    suspend fun restoreSubscriptionEnabled(
        subscriptionId: String,
        enabled: Boolean,
        selectedNodeId: String?,
    ) {
        setSubscriptionEnabled(subscriptionId, enabled)
        if (selectedNodeId != null) {
            putSetting(AppSettingEntity(AkihaLinkRepository.SETTING_SELECTED_NODE, selectedNodeId))
        }
    }

    @Transaction
    suspend fun replaceExcludedApps(apps: List<ExcludedAppEntity>) {
        clearExcludedApps()
        if (apps.isNotEmpty()) upsertExcludedApps(apps)
    }
}
