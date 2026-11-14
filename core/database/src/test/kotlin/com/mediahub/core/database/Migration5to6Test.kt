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
 * 搜索历史（条目 52）：Room 迁移 5→6 新增 search_history 表。
 *
 * 与 Migration4to5Test 同手法：Robolectric + FrameworkSQLiteOpenHelper 建 v5 空库，
 * 执行 MIGRATION_5_6 后校验表结构、可写入/读取以及主键去重。
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
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migrate 5 to 6 creates search history table and accepts rows`() {
        val db = openV5Database()
        try {
            Migrations.MIGRATION_5_6.migrate(db)

            db.execSQL(
                "INSERT INTO search_history (query, updatedAtEpochMs) VALUES ('冰血暴', 100)",
            )
            db.execSQL(
                "INSERT INTO search_history (query, updatedAtEpochMs) VALUES ('fargo', 200)",
            )

            db.query("SELECT query, updatedAtEpochMs FROM search_history ORDER BY updatedAtEpochMs DESC")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("fargo", cursor.getString(0))
                    assertEquals(200L, cursor.getLong(1))
                    assertTrue(cursor.moveToNext())
                    assertEquals("冰血暴", cursor.getString(0))
                }

            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO search_history (query, updatedAtEpochMs) VALUES ('fargo', 300)",
                )
            }
            assertTrue("query 必须是主键（重复写入应被拒绝）", duplicate.isFailure)
        } finally {
            db.close()
        }
    }
}
