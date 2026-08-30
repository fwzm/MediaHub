package com.mediahub.provider.jellyfin.session

import com.mediahub.provider.api.ProviderSessionCleaner
import javax.inject.Inject
import javax.inject.Singleton

/** Jellyfin 会话清理贡献者（ADR-039）：删除媒体源时清除 Jellyfin 会话元数据。 */
@Singleton
class JellyfinSessionCleaner @Inject constructor(
    storage: JellyfinSessionStore.Storage,
) : ProviderSessionCleaner {
    private val sessionStore = JellyfinSessionStore(storage)
    override suspend fun clear(serverId: String) {
        sessionStore.clear(serverId)
    }
}
