package com.mediahub.provider.emby.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Emby 会话元数据存取（非敏感；Token 本体由 core:security TokenStore 管理，见 ADR-026）。
 * 通过 [Storage] 抽象便于 JVM 单测（内存 fake）。
 */
class EmbySessionStore(private val storage: Storage) {

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

    suspend fun save(session: EmbySession) = withContext(Dispatchers.IO) {
        storage.put(session.localServerId, Json.encodeToString(EmbySession.serializer(), session))
    }

    suspend fun read(localServerId: String): EmbySession? = withContext(Dispatchers.IO) {
        storage.get(localServerId)?.let { raw ->
            runCatching { Json.decodeFromString(EmbySession.serializer(), raw) }.getOrNull()
        }
    }

    suspend fun clear(localServerId: String) = withContext(Dispatchers.IO) {
        storage.remove(localServerId)
    }

}
