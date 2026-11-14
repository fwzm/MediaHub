package com.mediahub.core.database.repository

import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.SearchHistoryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史读写面（条目 52/120）。[SearchHistoryRepository] 是唯一生产实现。
 */
interface SearchHistoryStore {
    /** 按最近使用倒序观察历史，最多 [limit] 条。 */
    fun observe(limit: Int = SearchHistoryRepository.MAX_ENTRIES): Flow<List<String>>

    /** 记录一次搜索：去空白、去重（重复搜索刷新到最前），并裁剪到上限。 */
    suspend fun record(query: String)

    /** 长按删除单条历史。 */
    suspend fun remove(query: String)
}

/**
 * 本地搜索历史（Room 持久化，条目 52/120）。
 *
 * 复用项目既有 Room 方案，不引入新依赖；[clock] 可注入以便测试稳定断言先后顺序。
 */
@Singleton
class SearchHistoryRepository internal constructor(
    db: AppDatabase,
    private val clock: () -> Long,
) : SearchHistoryStore {

    @Inject
    constructor(db: AppDatabase) : this(db, System::currentTimeMillis)

    private val dao = db.searchHistoryDao()

    override fun observe(limit: Int): Flow<List<String>> = dao.observeRecent(limit)

    override suspend fun record(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        dao.upsert(SearchHistoryEntity(query = normalized, updatedAtEpochMs = clock()))
        dao.trimTo(MAX_ENTRIES)
    }

    override suspend fun remove(query: String) {
        dao.delete(query.trim())
    }

    companion object {
        /** 搜索历史持久化上限（UI 固定展示两行，超出滚动）。 */
        const val MAX_ENTRIES = 20
    }
}
