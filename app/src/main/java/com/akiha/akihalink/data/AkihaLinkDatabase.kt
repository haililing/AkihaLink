package com.akiha.akihalink.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SubscriptionEntity::class,
        ProxyNodeEntity::class,
        ExcludedAppEntity::class,
        AppSettingEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AkihaLinkDatabase : RoomDatabase() {
    abstract fun dao(): AkihaLinkDao

    companion object {
        @Volatile private var instance: AkihaLinkDatabase? = null

        fun get(context: Context): AkihaLinkDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AkihaLinkDatabase::class.java,
                "akihalink.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .build()
                .also { instance = it }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN createdAt INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN trafficUploadBytes INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN trafficDownloadBytes INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN trafficTotalBytes INTEGER")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN expiresAt INTEGER")
                db.execSQL("UPDATE subscriptions SET createdAt = lastUpdatedAt WHERE createdAt IS NULL")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS excluded_apps_v4 (
                        userId INTEGER NOT NULL,
                        packageName TEXT NOT NULL,
                        signingCertificateSha256 TEXT NOT NULL,
                        uid INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        PRIMARY KEY(userId, packageName)
                    )
                    """.trimIndent(),
                )
                // Version 3 did not retain a signing identity. Keep those rows
                // quarantined with an empty digest so they can never bypass a
                // newly installed package that happens to inherit the old UID.
                db.execSQL(
                    """
                    INSERT INTO excluded_apps_v4
                        (userId, packageName, signingCertificateSha256, uid, label)
                    SELECT uid / 100000, packageName, '', uid, label FROM excluded_apps
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE excluded_apps")
                db.execSQL("ALTER TABLE excluded_apps_v4 RENAME TO excluded_apps")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_excluded_apps_uid ON excluded_apps (uid)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN refreshIntervalHours INTEGER")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN subscriptionStartedAt INTEGER")
            }
        }
    }
}
