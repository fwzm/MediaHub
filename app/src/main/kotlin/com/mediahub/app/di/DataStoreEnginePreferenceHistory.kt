package com.mediahub.app.di

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediahub.player.engine.EnginePreferenceHistory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.engineHistoryDataStore by preferencesDataStore(name = "engine_history")

/**
 * 引擎失败指纹持久化（U3-A，DataStore stringSet；app 层实现——需同时依赖
 * player:engine 接口与 DataStore）。
 *
 * 内存缓存 + 后台加载：snapshot 读取无挂起（选择器在 play() 同步路径使用）；
 * 记录先更新缓存再落盘，进程被杀最多丢最后一次指纹（可接受的代价）。
 */
@Singleton
class DataStoreEnginePreferenceHistory @Inject constructor(
    @ApplicationContext private val context: Context,
) : EnginePreferenceHistory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            runCatching {
                cache.value = context.engineHistoryDataStore.data.first()[MPV_PREFERRED] ?: emptySet()
            }
        }
    }

    override fun mpvPreferredSignatures(): Set<String> = cache.value

    override suspend fun recordMedia3Failure(signatureKey: String) {
        cache.value = cache.value + signatureKey
        runCatching {
            context.engineHistoryDataStore.edit { prefs ->
                prefs[MPV_PREFERRED] = prefs[MPV_PREFERRED].orEmpty() + signatureKey
            }
        }
    }

    private companion object {
        val MPV_PREFERRED = stringSetPreferencesKey("mpv_preferred_signatures")
    }
}
