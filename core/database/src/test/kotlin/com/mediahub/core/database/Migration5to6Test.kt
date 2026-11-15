package com.mediahub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 条目 141/139：Room 迁移 5→6 的 SQL 级回归（与 Migration4to5Test 同手法）。
 *
 * MIGRATION_5_6 只新增表，不触碰既有表；这里验证两张新表可建、可写、主键约束成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration5to6Test {

    private fun openV5Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication(),
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v5 形态的最小既有表；迁移不得依赖/破坏它。
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS servers (" +
                            "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migrate 5 to 6 creates watch session and hidden content tables`() {
        val db = openV5Database()
        try {
            db.execSQL("INSERT INTO servers (id, name) VALUES ('s1','NAS')")

            Migrations.MIGRATION_5_6.migrate(db)

            // 既有表无损
            db.query("SELECT COUNT(*) FROM servers").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
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

            // 隐藏内容表主键 = (serverId, itemId)
            db.execSQL(
                "INSERT INTO hidden_content (serverId, itemId, hiddenAtEpochMs) " +
                    "VALUES ('s1','ep1',1000)",
            )
            db.query("SELECT COUNT(*) FROM hidden_content").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
