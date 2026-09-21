package com.mediahub.provider.emby.session

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Emby 会话元数据存取（非敏感；Token 本体由 core:security TokenStore 管理，见 ADR-026）。
 * 通过 [Storage] 抽象便于 JVM 单测（内存 fake）。
 *
 * [ioDispatcher] 可注入（Phase 1I review round-4）：默认 Dispatchers.IO；测试传入
 * TestDispatcher 后，`requireSession` → mutex 全链路调度可被测试调度器确定性控制。
 */
class EmbySessionStore(
    private val storage: Storage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    interface Storage {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** 生产实现：SharedPreferences（小数据、非敏感）。 */
    class SharedPrefsStorage(context: Context) : Storage {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = prefs.edit().remove(key).apply()

        private companion object {
            const val PREFS_NAME = "mediahub_emby_sessions"
        }
    }

    suspend fun save(session: EmbySession) = withContext(ioDispatcher) {
        storage.put(session.localServerId, Json.encodeToString(EmbySession.serializer(), session))
    }

    suspend fun read(localServerId: String): EmbySession? = withContext(ioDispatcher) {
        storage.get(localServerId)?.let { raw ->
            runCatching { Json.decodeFromString(EmbySession.serializer(), raw) }.getOrNull()
        }
    }

    suspend fun clear(localServerId: String) = withContext(ioDispatcher) {
        storage.remove(localServerId)
    }

}
