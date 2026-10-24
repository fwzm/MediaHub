package com.mediahub.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room 迁移（Phase 1B-2.5：servers 去 baseUrl、加 note/icon；新增 server_endpoints 表）。 */
object Migrations {

    /** P2 字幕中心切片一：新增 subtitle_memory 表（匹配记忆，键=视频版本指纹）。 */
    /**
     * W6-B：servers 加跨设备来源同步身份（ADR-S001）。
     *
     * - sync_source_id：可空（旧记录没有同步身份）+ 唯一索引（同一个同步身份只能对应一条本地来源）；
     *   SQLite 的唯一索引允许多个 NULL，所以未同步的旧记录不会互相冲突。
     * - sync_record_revision：非空默认 0，旧记录视为"从未同步"。
     * - sync_source_type_raw：原始 wire 类型逐字保留，未知类型不丢弃（D3）。
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE servers ADD COLUMN sync_source_id TEXT")
            db.execSQL("ALTER TABLE servers ADD COLUMN sync_record_revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE servers ADD COLUMN sync_source_type_raw TEXT")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_servers_sync_source_id ON servers(sync_source_id)",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS subtitle_memory (" +
                    "versionKey TEXT NOT NULL, " +
                    "serverId TEXT NOT NULL, " +
                    "subtitleId TEXT, " +
                    "offsetMs INTEGER NOT NULL, " +
                    "updatedAtEpochMs INTEGER NOT NULL, " +
                    "PRIMARY KEY(versionKey))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_subtitle_memory_serverId ON subtitle_memory(serverId)")
        }
    }

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