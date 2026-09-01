package com.akiha.akihalink.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val encryptedUrl: String,
    val lastUpdatedAt: Long? = null,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "1") val isEnabled: Boolean = true,
    val createdAt: Long? = null,
    val trafficUploadBytes: Long? = null,
    val trafficDownloadBytes: Long? = null,
    val trafficTotalBytes: Long? = null,
    val expiresAt: Long? = null,
    val refreshIntervalHours: Long? = null,
    val subscriptionStartedAt: Long? = null,
)

data class SubscriptionNodeCount(
    val subscriptionId: String,
    val nodeCount: Int,
)

@Entity(
    tableName = "proxy_nodes",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("subscriptionId"), Index("fingerprint")],
)
data class ProxyNodeEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val fingerprint: String,
    val name: String,
    val protocol: String,
    val endpoint: String,
    val encryptedOutbound: String,
)

@Entity(
    tableName = "excluded_apps",
    primaryKeys = ["userId", "packageName"],
    indices = [Index("uid")],
)
data class ExcludedAppEntity(
    val userId: Int,
    val packageName: String,
    val signingCertificateSha256: String,
    val uid: Int,
    val label: String,
)

@Entity(tableName = "settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
