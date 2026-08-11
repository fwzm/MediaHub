package com.mediahub.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mediahub.core.database.dao.AccountDao
import com.mediahub.core.database.dao.PlaybackProgressDao
import com.mediahub.core.database.dao.ServerDao
import com.mediahub.core.database.entity.AccountEntity
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.ServerEntity

@Database(
    entities = [
        ServerEntity::class,
        AccountEntity::class,
        PlaybackProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun accountDao(): AccountDao
    abstract fun playbackProgressDao(): PlaybackProgressDao

    companion object {
        const val NAME = "mediahub.db"
    }
}
