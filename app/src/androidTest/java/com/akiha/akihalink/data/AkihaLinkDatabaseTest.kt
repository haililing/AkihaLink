package com.akiha.akihalink.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AkihaLinkDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: AkihaLinkDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(MIGRATION_DATABASE)
    }

    @Test
    fun migrationFromOnePreservesSubscriptionsAndAddsOverviewFields() {
        createVersionOneDatabase()

        val migrated = Room.databaseBuilder(context, AkihaLinkDatabase::class.java, MIGRATION_DATABASE)
            .addMigrations(
                AkihaLinkDatabase.MIGRATION_1_2,
                AkihaLinkDatabase.MIGRATION_2_3,
                AkihaLinkDatabase.MIGRATION_3_4,
                AkihaLinkDatabase.MIGRATION_4_5,
                AkihaLinkDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
            .also { database = it }

        val subscription = migrated.openHelper.writableDatabase.query(
            """
            SELECT isEnabled, createdAt, trafficUploadBytes, trafficDownloadBytes,
                trafficTotalBytes, expiresAt, refreshIntervalHours, subscriptionStartedAt
            FROM subscriptions WHERE id = 'sub'
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            cursor.getInt(0) to cursor.getLong(1)
        }

        assertEquals(1 to 1_700_000_000_000L, subscription)
        assertEquals(listOf("sub:node"), runBlocking { migrated.dao().getNodes().map { it.id } })
    }

    @Test
    fun migrationFromThreeQuarantinesUidOnlyExclusions() {
        createVersionThreeDatabase()

        val migrated = Room.databaseBuilder(context, AkihaLinkDatabase::class.java, MIGRATION_DATABASE)
            .addMigrations(
                AkihaLinkDatabase.MIGRATION_3_4,
                AkihaLinkDatabase.MIGRATION_4_5,
                AkihaLinkDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()
            .also { database = it }

        val exclusion = runBlocking { migrated.dao().getExcludedApps().single() }
        assertEquals(0, exclusion.userId)
        assertEquals("dev.example.legacy", exclusion.packageName)
        assertEquals("", exclusion.signingCertificateSha256)
        assertEquals(10_101, exclusion.uid)
    }

    @Test
    fun disabledSubscriptionsAreExcludedWithoutDeletingNodesOrCounts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AkihaLinkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        val dao = db.dao()
        dao.replaceSubscription(subscription("enabled"), listOf(node("enabled")))
        dao.replaceSubscription(subscription("disabled", isEnabled = false), listOf(node("disabled")))

        assertEquals(listOf("enabled:node"), dao.getNodes().map { it.id })
        assertEquals(setOf("enabled:node", "disabled:node"), dao.getAllNodes().map { it.id }.toSet())
        assertEquals(
            mapOf("enabled" to 1, "disabled" to 1),
            dao.observeSubscriptionNodeCounts().first().associate { it.subscriptionId to it.nodeCount },
        )
    }

    private fun createVersionOneDatabase() {
        context.deleteDatabase(MIGRATION_DATABASE)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(MIGRATION_DATABASE)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            VERSION_ONE_SCHEMA.forEach(db::execSQL)
                            db.execSQL(
                                """
                                INSERT INTO subscriptions (id, name, encryptedUrl, lastUpdatedAt)
                                VALUES ('sub', 'Subscription', 'encrypted', 1700000000000)
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO proxy_nodes
                                    (id, subscriptionId, fingerprint, name, protocol, endpoint, encryptedOutbound)
                                VALUES
                                    ('sub:node', 'sub', 'fingerprint', 'Node', 'trojan', 'example.com:443', 'encrypted')
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            helper.writableDatabase
        } finally {
            try {
                helper.close()
            } catch (_: IOException) {
                // The migration open below is the authoritative validation.
            }
        }
    }

    private fun createVersionThreeDatabase() {
        context.deleteDatabase(MIGRATION_DATABASE)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(MIGRATION_DATABASE)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            VERSION_ONE_SCHEMA.forEach(db::execSQL)
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN createdAt INTEGER")
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN trafficUploadBytes INTEGER")
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN trafficDownloadBytes INTEGER")
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN trafficTotalBytes INTEGER")
                            db.execSQL("ALTER TABLE subscriptions ADD COLUMN expiresAt INTEGER")
                            db.execSQL(
                                """
                                INSERT INTO excluded_apps (uid, packageName, label)
                                VALUES (10101, 'dev.example.legacy', 'Legacy')
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            helper.writableDatabase
        } finally {
            try {
                helper.close()
            } catch (_: IOException) {
                // The migration open below is the authoritative validation.
            }
        }
    }

    private fun subscription(id: String, isEnabled: Boolean = true) = SubscriptionEntity(
        id = id,
        name = id,
        encryptedUrl = "encrypted",
        isEnabled = isEnabled,
    )

    private fun node(subscriptionId: String) = ProxyNodeEntity(
        id = "$subscriptionId:node",
        subscriptionId = subscriptionId,
        fingerprint = "$subscriptionId-fingerprint",
        name = subscriptionId,
        protocol = "trojan",
        endpoint = "example.com:443",
        encryptedOutbound = "encrypted",
    )

    private companion object {
        const val MIGRATION_DATABASE = "subscription-toggle-migration.db"

        val VERSION_ONE_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS subscriptions (id TEXT NOT NULL, name TEXT NOT NULL, encryptedUrl TEXT NOT NULL, lastUpdatedAt INTEGER, lastError TEXT, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS proxy_nodes (id TEXT NOT NULL, subscriptionId TEXT NOT NULL, fingerprint TEXT NOT NULL, name TEXT NOT NULL, protocol TEXT NOT NULL, endpoint TEXT NOT NULL, encryptedOutbound TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS index_proxy_nodes_subscriptionId ON proxy_nodes (subscriptionId)",
            "CREATE INDEX IF NOT EXISTS index_proxy_nodes_fingerprint ON proxy_nodes (fingerprint)",
            "CREATE TABLE IF NOT EXISTS excluded_apps (uid INTEGER NOT NULL, packageName TEXT NOT NULL, label TEXT NOT NULL, PRIMARY KEY(uid))",
            "CREATE TABLE IF NOT EXISTS settings (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}
