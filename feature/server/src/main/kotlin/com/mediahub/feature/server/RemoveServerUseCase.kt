package com.mediahub.feature.server

import com.mediahub.core.database.repository.AccountRepository
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.SessionStoreCleaner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除媒体源（级联清理，Server Editor 危险区）。
 *
 * 清理：server(+endpoints) → account → token → credential → progress → 自定义图标文件 → provider 会话。
 * 若删除的是默认媒体源，ServerRepository.deleteServer 会重选首条为默认（保持最多一个 default 不变式）。
 */
@Singleton
class RemoveServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val progressRepository: ProgressRepository,
    private val accountRepository: AccountRepository,
    private val tokenStore: TokenStore,
    private val credentialVault: CredentialVault,
    private val serverIconStore: ServerIconStore,
    private val sessionStoreCleaner: SessionStoreCleaner,
) {
    suspend operator fun invoke(server: MediaServer) {
        serverRepository.deleteServer(server.id)
        accountRepository.deleteForServer(server.id)
        tokenStore.clear(server.id)
        credentialVault.clear(server.id)
        progressRepository.deleteByServer(server.id)
        serverIconStore.remove(server.id)
        sessionStoreCleaner.clear(server.id)
    }
}
