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
 * 条目 146：Room 迁移 6→7（新增本地追更表）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration6to7Test {

    private fun openV6Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication(),
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migrate 6 to 7 creates tracked shows table`() {
        val db = openV6Database()
        try {
            Migrations.MIGRATION_6_7.migrate(db)
            db.execSQL(
                "INSERT INTO trakt_tracked_shows (traktId, title, year, addedAtEpochMs) " +
                    "VALUES (123,'测试剧集',2024,1000)",
            )
            db.query("SELECT title FROM trakt_tracked_shows WHERE traktId = 123").use { c ->
                c.moveToFirst()
                assertEquals("测试剧集", c.getString(0))
            }
        } finally {
            db.close()
        }
    }
}
