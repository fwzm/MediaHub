package com.mediahub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A4：Room 迁移 3→4 的 SQL 级回归（Robolectric + FrameworkSQLiteOpenHelper）。
 *
 * 边界（如实登记）：Room 2.8.4 的 MigrationTestHelper 在 Robolectric 下存在
 * databaseName/绝对路径解析不兼容（IllegalArgumentException: driver configured
 * … but … was requested），全链 helper 验证（含 v3 历史结构与 identity hash
 * 校验）在本环境 BLOCKED_ENV；identity hash 与 entity 一致性由 KSP 导出的
 * schemas/4.json（编译期）覆盖。本测试验证 MIGRATION_3_4 的 SQL 正确性：
 * 在 v3 形态库上执行迁移，断言 subtitle_memory 表结构、索引与既有数据无损。
 * 备份兼容性：subtitle_memory 不在备份 DTO——恢复旧备份与本表无冲突。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration3to4Test {

    private fun openV3Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication(),
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v3 既有表（结构与 schemas/3.json 对齐的最小子集：servers 相关
                    // 为迁移保留性验证，subtitle_memory 尚不存在）
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS servers (" +
                            "id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, " +
                            "username TEXT, note TEXT, icon TEXT, isDefault INTEGER NOT NULL DEFAULT 0, " +
                            "sortOrder INTEGER NOT NULL DEFAULT 0, createdAtEpochMs INTEGER NOT NULL, " +
                            "lastConnectedAtEpochMs INTEGER, lastError TEXT, PRIMARY KEY(id))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS playback_progress (" +
                            "serverId TEXT NOT NULL, itemId TEXT NOT NULL, positionMs INTEGER NOT NULL, " +
                            "durationMs INTEGER NOT NULL, finished INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, " +
                            "PRIMARY KEY(serverId, itemId))",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        // db 版本固定在 3：迁移不经过 onUpgrade，直接调用 MIGRATION_3_4.migrate
        return db
    }

    @Test
    fun `migrate 3 to 4 creates subtitle_memory index and preserves existing data`() {
        val db = openV3Database()
        try {
            db.execSQL(
                "INSERT INTO servers (id, name, type, isDefault, sortOrder, createdAtEpochMs) " +
                    "VALUES ('s1','NAS','WEBDAV',1,0,0)",
            )
            db.execSQL(
                "INSERT INTO playback_progress (serverId, itemId, positionMs, durationMs, finished, updatedAtEpochMs) " +
                    "VALUES ('s1','m1',1000,60000,0,1)",
            )

            Migrations.MIGRATION_3_4.migrate(db)

            // 既有数据无损
            db.query("SELECT COUNT(*) FROM servers").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM playback_progress").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            // 新表结构与 4.json 对齐
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='subtitle_memory'",
            ).use { c -> assertTrue("subtitle_memory 表必须存在", c.moveToFirst()) }
            db.execSQL(
                "INSERT INTO subtitle_memory (versionKey, serverId, subtitleId, offsetMs, updatedAtEpochMs) " +
                    "VALUES ('k1','s1','sub-a',1200,1)",
            )
            db.query("SELECT subtitleId, offsetMs FROM subtitle_memory WHERE versionKey='k1'").use { c ->
                c.moveToFirst()
                assertEquals("sub-a", c.getString(0))
                assertEquals(1200L, c.getLong(1))
            }
            // 主键去重（版本指纹）：同 key 二次插入必须被 SQLite 拒绝
            val dup = runCatching {
                db.execSQL(
                    "INSERT INTO subtitle_memory (versionKey, serverId, subtitleId, offsetMs, updatedAtEpochMs) " +
                        "VALUES ('k1','s1','sub-b',0,2)",
                )
            }.isFailure
            assertTrue("versionKey 主键必须去重", dup)
            // serverId 索引（删源级联路径）
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='subtitle_memory'",
            ).use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                assertTrue("serverId 索引必须存在: $names", names.any { it.contains("serverId") })
            }
        } finally {
            db.close()
        }
    }
}
