package com.mediahub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W6-B：Room 迁移 4→5 的 SQL 级回归（Robolectric + FrameworkSQLiteOpenHelper，与 Migration3to4Test 同手法）。
 *
 * 验证 MIGRATION_4_5：servers 增加同步身份三列、既有行不受影响、sync_source_id 唯一索引成立
 * 且允许多个 NULL（未同步的旧记录不会互相冲突）。全链连续迁移在 instrumented
 * RoomMigrationDeviceTest 覆盖，本环境不跑 instrumentation。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration4to5Test {

    private fun openV4Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication(),
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v4 形态的 servers（与 schemas/4.json 的 createSql 逐列一致，尚无同步三列）
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS servers (" +
                            "id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, " +
                            "username TEXT, note TEXT, icon TEXT, isDefault INTEGER NOT NULL, " +
                            "sortOrder INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, " +
                            "lastConnectedAtEpochMs INTEGER, lastError TEXT, PRIMARY KEY(id))",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migrate 4 to 5 adds sync identity columns with defaults and keeps rows`() {
        val db = openV4Database()
        try {
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs) " +
                    "VALUES ('s1','NAS','EMBY',1,0,0)",
            )
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs) " +
                    "VALUES ('s2','云盘','QUARK_DRIVE',0,1,0)",
            )

            Migrations.MIGRATION_4_5.migrate(db)

            // 既有行无损
            db.query("SELECT COUNT(*) FROM servers").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            // 旧行在新列上取默认值：null / 0 / null
            db.query(
                "SELECT sync_source_id, sync_record_revision, sync_source_type_raw " +
                    "FROM servers ORDER BY id",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertNull(c.getString(0))
                assertEquals(0L, c.getLong(1))
                assertNull(c.getString(2))
                assertTrue(c.moveToNext())
                assertNull(c.getString(0))
                assertEquals(0L, c.getLong(1))
            }
            // 列定义：sync_record_revision NOT NULL DEFAULT 0，sync_source_id 可空
            val columns = mutableMapOf<String, Triple<Int, String?, Int>>()
            db.query("PRAGMA table_info(servers)").use { c ->
                while (c.moveToNext()) {
                    columns[c.getString(1)] = Triple(c.getInt(3), c.getString(4), c.getInt(5))
                }
            }
            assertTrue("sync_source_id 列必须存在: " + columns.keys, "sync_source_id" in columns)
            assertTrue("sync_record_revision 列必须存在: " + columns.keys, "sync_record_revision" in columns)
            assertTrue("sync_source_type_raw 列必须存在: " + columns.keys, "sync_source_type_raw" in columns)
            assertEquals(0, columns.getValue("sync_source_id").first)
            assertEquals(1, columns.getValue("sync_record_revision").first)
            assertEquals("0", columns.getValue("sync_record_revision").second)
            // 唯一索引
            db.query("PRAGMA index_list(servers)").use { c ->
                val unique = mutableMapOf<String, Int>()
                while (c.moveToNext()) unique[c.getString(1)] = c.getInt(2)
                assertEquals(
                    "index_servers_sync_source_id 必须是唯一索引: " + unique,
                    1,
                    unique["index_servers_sync_source_id"],
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `unique index rejects duplicate identity while allowing many nulls`() {
        val db = openV4Database()
        try {
            Migrations.MIGRATION_4_5.migrate(db)
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs, sync_source_id) " +
                    "VALUES ('s1','A','EMBY',0,0,0,'local-1')",
            )
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs) " +
                    "VALUES ('s2','B','EMBY',0,1,0)",
            )
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs) " +
                    "VALUES ('s3','C','EMBY',0,2,0)",
            )
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs, sync_source_id) " +
                    "VALUES ('s4','D','EMBY',0,3,0,'local-2')",
            )
            // 同一同步身份绑两条本地来源必须被拒（唯一索引）
            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs, sync_source_id) " +
                        "VALUES ('s5','E','EMBY',0,4,0,'local-1')",
                )
            }
            assertTrue("重复 sync_source_id 必须被唯一索引拒绝", duplicate.isFailure)
            // 未同步的记录可以有任意多条 NULL
            db.query("SELECT COUNT(*) FROM servers WHERE sync_source_id IS NULL").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
