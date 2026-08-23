package com.mediahub.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room 迁移（Phase 1B-2.5：servers 去 baseUrl、加 note/icon；新增 server_endpoints 表）。 */
object Migrations {

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE server_endpoints ADD COLUMN lastApiLatencyMs INTEGER")
            db.execSQL("ALTER TABLE server_endpoints ADD COLUMN lastMediaFirstByteMs INTEGER")
            db.execSQL("ALTER TABLE server_endpoints ADD COLUMN lastMediaThroughputMbps REAL")
            db.execSQL("ALTER TABLE server_endpoints ADD COLUMN lastProtocol TEXT")
            db.execSQL("ALTER TABLE server_endpoints ADD COLUMN lastSupportsRange INTEGER")
            db.execSQL("ALTER TABLE server_endpoints ADD COLUMN lastHttpCode INTEGER")
        }
    }

       val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS server_endpoints (" +
                    "id TEXT NOT NULL, " +
                    "serverId TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "isPrimary INTEGER NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "sortOrder INTEGER NOT NULL, " +
                    "lastLatencyMs INTEGER, " +
                    "lastError TEXT, " +
                    "lastTestedAtEpochMs INTEGER, " +
                    "PRIMARY KEY(id))",
            )

            db.execSQL(
                "INSERT INTO server_endpoints " +
                    "(id, serverId, name, url, isPrimary, enabled, sortOrder) " +
                    "SELECT id || '_ep0', id, '默认线路', baseUrl, 1, 1, 0 " +
                    "FROM servers WHERE baseUrl IS NOT NULL AND baseUrl != ''",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS servers_new (" +
                    "id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "username TEXT, " +
                    "note TEXT, " +
                    "icon TEXT, " +
                    "isDefault INTEGER NOT NULL, " +
                    "sortOrder INTEGER NOT NULL, " +
                    "createdAtEpochMs INTEGER NOT NULL, " +
                    "lastConnectedAtEpochMs INTEGER, " +
                    "lastError TEXT, " +
                    "PRIMARY KEY(id))",
            )

            db.execSQL(
                "INSERT INTO servers_new " +
                    "(id, name, type, username, isDefault, sortOrder, " +
                    "createdAtEpochMs, lastConnectedAtEpochMs, lastError) " +
                    "SELECT id, name, type, username, isDefault, sortOrder, " +
                    "createdAtEpochMs, lastConnectedAtEpochMs, lastError FROM servers",
            )

            db.execSQL("DROP TABLE servers")
            db.execSQL("ALTER TABLE servers_new RENAME TO servers")
        }
    }
}