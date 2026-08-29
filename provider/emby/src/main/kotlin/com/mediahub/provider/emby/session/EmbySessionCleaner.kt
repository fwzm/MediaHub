package com.mediahub.provider.emby.session

import com.mediahub.provider.api.ProviderSessionCleaner
import javax.inject.Inject
import javax.inject.Singleton

/** Emby 会话清理贡献者（ADR-039）：删除媒体源时清除 Emby 会话元数据。 */
@Singleton
class EmbySessionCleaner @Inject constructor(
    storage: EmbySessionStore.Storage,
) : ProviderSessionCleaner {
    private val sessionStore = EmbySessionStore(storage)
    override suspend fun clear(serverId: String) {
        sessionStore.clear(serverId)
    }
}
