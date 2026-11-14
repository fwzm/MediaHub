package com.mediahub.feature.library.iptv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * IPTV 频道本地收藏。
 *
 * 为什么不走 Provider 的 `FAVORITE` 能力：M3U 源没有服务端收藏语义，
 * 收藏只属于本机用户偏好；因此存 DataStore，按 serverId 分组保存频道 id，
 * 不保存频道名/logo，更不保存直播流地址（ADR-003）。
 */
interface IptvFavoritesRepository {
    fun favorites(serverId: String): Flow<Set<String>>

    /** 切换收藏；返回切换后是否处于收藏状态。 */
    suspend fun toggle(serverId: String, channelId: String): Boolean
}

private val Context.iptvFavoritesStore by preferencesDataStore(name = "iptv_favorites")

@Singleton
class DataStoreIptvFavoritesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : IptvFavoritesRepository {

    override fun favorites(serverId: String): Flow<Set<String>> =
        context.iptvFavoritesStore.data.map { prefs -> prefs[key(serverId)].orEmpty() }

    override suspend fun toggle(serverId: String, channelId: String): Boolean {
        var nowFavorite = false
        context.iptvFavoritesStore.edit { prefs ->
            val key = key(serverId)
            val current = prefs[key].orEmpty().toMutableSet()
            nowFavorite = current.add(channelId)
            if (!nowFavorite) current.remove(channelId)
            prefs[key] = current
        }
        return nowFavorite
    }

    private fun key(serverId: String) = stringSetPreferencesKey("server_$serverId")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IptvFavoritesModule {
    @Binds
    @Singleton
    abstract fun bindIptvFavoritesRepository(
        repository: DataStoreIptvFavoritesRepository,
    ): IptvFavoritesRepository
}
