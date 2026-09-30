package com.mediahub.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated historical-schema databases. This does not inspect or upgrade user data. */
@RunWith(AndroidJUnit4::class)
class RoomMigrationDeviceTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun historicalSchemasMigrateAndReopenThroughActualRoom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in 1..3) {
            val name = "b-a4-room-migration-$version"
            context.deleteDatabase(name)
            try {
                helper.createDatabase(name, version).use { db ->
                    val legacyAddress = if (version == 1) ", baseUrl" else ""
                    val legacyValue = if (version == 1) ", 'https://fixture.invalid'" else ""
                    db.execSQL("INSERT INTO servers (id,name,type,isDefault,sortOrder,createdAtEpochMs$legacyAddress) " +
                        "VALUES ('s1','Historical NAS','WEBDAV',1,0,123$legacyValue)")
                    db.execSQL("INSERT INTO playback_progress (serverId,itemId,positionMs,durationMs,isPaused,updatedAtEpochMs) " +
                        "VALUES ('s1','movie1',12345,60000,1,456)")
                    if (version >= 2) db.execSQL("INSERT INTO server_endpoints (id,serverId,name,url,isPrimary,enabled,sortOrder) " +
                        "VALUES ('s1_ep0','s1','main','https://fixture.invalid',1,1,0)")
                }
                helper.runMigrationsAndValidate(name, 4, true, Migrations.MIGRATION_1_2,
                    Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4).close()
                val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)
                    .allowMainThreadQueries().build()
                try {
                        // Opening through the real generated database also verifies the stored identity hash.
                        val db = room.openHelper.writableDatabase
                        assertEquals(4, db.version)
                        db.query("SELECT name,createdAtEpochMs FROM servers WHERE id='s1'").use { c ->
                            assertTrue(c.moveToFirst()); assertEquals("Historical NAS", c.getString(0)); assertEquals(123L, c.getLong(1))
                        }
                        db.query("SELECT positionMs,isPaused FROM playback_progress WHERE serverId='s1' AND itemId='movie1'").use { c ->
                            assertTrue(c.moveToFirst()); assertEquals(12345L, c.getLong(0)); assertEquals(1, c.getInt(1))
                        }
                        db.query("SELECT url FROM server_endpoints WHERE serverId='s1'").use { c ->
                            assertTrue(c.moveToFirst()); assertEquals("https://fixture.invalid", c.getString(0))
                        }
                        db.execSQL("INSERT INTO subtitle_memory VALUES ('s1|movie1|s:1','s1','fixture-sub',700,789)")
                        assertTrue(runCatching {
                            db.execSQL("INSERT INTO subtitle_memory VALUES ('s1|movie1|s:1','s1','other-sub',0,790)")
                        }.isFailure)
                        db.query("PRAGMA index_list(subtitle_memory)").use { c ->
                            var found = false
                            while (c.moveToNext()) if (c.getString(c.getColumnIndexOrThrow("name")) == "index_subtitle_memory_serverId") found = true
                            assertTrue("serverId index on historical version $version", found)
                        }
                } finally {
                    room.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }
}
