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
 * 观影统计（条目 141/139）：Room 迁移 6→7 的 SQL 级回归（与 Migration5to6Test 同手法）。
 *
 * main 的 v6 已由搜索历史占用，本迁移顺延到 7；这里验证两张新表可建、可写、主键约束成立，
 * 且 v6 既有的 search_history 表与数据不被破坏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration6to7Test {

    private fun openV6Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication(),
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v6 形态的最小既有表；迁移不得依赖/破坏它们。
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS servers (" +
                            "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS search_history (" +
                            "query TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, " +
                            "PRIMARY KEY(query))",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migrate 6 to 7 creates watch session and hidden content tables`() {
        val db = openV6Database()
        try {
            db.execSQL("INSERT INTO servers (id, name) VALUES ('s1','NAS')")
            db.execSQL("INSERT INTO search_history (query, updatedAtEpochMs) VALUES ('fargo', 200)")

            Migrations.MIGRATION_6_7.migrate(db)

            // v6 既有表与数据无损
            db.query("SELECT COUNT(*) FROM servers").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT updatedAtEpochMs FROM search_history WHERE query = 'fargo'").use { c ->
                c.moveToFirst()
                assertEquals(200L, c.getLong(0))
            }

            // 观影会话表可写可读
            db.execSQL(
                "INSERT INTO watch_sessions (" +
                    "id, serverId, itemId, itemType, title, watchedMs, startedAtEpochMs, " +
                    "endedAtEpochMs, positionStartMs, positionEndMs, durationMs, completed" +
                    ") VALUES ('w1','s1','ep1','EPISODE','剧集',60000,1000,61000,0,60000,1440000,0)",
            )
            db.query("SELECT watchedMs FROM watch_sessions WHERE id = 'w1'").use { c ->
                c.moveToFirst()
                assertEquals(60_000L, c.getLong(0))
            }

            // 隐藏内容表主键 = (serverId, itemId)，重复写入必须失败
            db.execSQL("INSERT INTO hidden_content (serverId, itemId, hiddenAtEpochMs) VALUES ('s1','ep1',1000)")
            assertTrue(
                runCatching {
                    db.execSQL("INSERT INTO hidden_content (serverId, itemId, hiddenAtEpochMs) VALUES ('s1','ep1',2000)")
                }.isFailure,
            )
        } finally {
            db.close()
        }
    }
}
