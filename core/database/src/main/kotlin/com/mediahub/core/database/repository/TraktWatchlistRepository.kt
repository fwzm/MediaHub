package com.mediahub.core.database.repository

import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.TraktTrackedShowEntity
import com.mediahub.model.TraktTrackedShow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 本地追更列表读写面（条目 146）。 */
interface TraktWatchlistStore {
    fun observeTracked(): Flow<List<TraktTrackedShow>>
    suspend fun allTracked(): List<TraktTrackedShow>
    suspend fun isTracked(traktId: Int): Boolean
    suspend fun add(show: TraktTrackedShow)
    suspend fun remove(traktId: Int)
}

@Singleton
class RoomTraktWatchlistRepository @Inject constructor(
    db: AppDatabase,
) : TraktWatchlistStore {
    private val dao = db.traktWatchlistDao()

    override fun observeTracked(): Flow<List<TraktTrackedShow>> =
        dao.observeAll().map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun allTracked(): List<TraktTrackedShow> =
        dao.getAll().map { it.toDomain() }

    override suspend fun isTracked(traktId: Int): Boolean = dao.contains(traktId)

    override suspend fun add(show: TraktTrackedShow) {
        dao.upsert(
            TraktTrackedShowEntity(
                traktId = show.traktId,
                title = show.title,
                year = show.year,
                addedAtEpochMs = show.addedAtEpochMs,
            ),
        )
    }

    override suspend fun remove(traktId: Int) = dao.delete(traktId)

    private fun TraktTrackedShowEntity.toDomain() = TraktTrackedShow(
        traktId = traktId,
        title = title,
        year = year,
        addedAtEpochMs = addedAtEpochMs,
    )
}
