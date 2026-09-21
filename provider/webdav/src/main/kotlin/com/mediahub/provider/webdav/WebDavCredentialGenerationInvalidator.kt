package com.mediahub.provider.webdav

import com.mediahub.provider.api.CredentialGenerationInvalidator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 WebDAV 凭据协调器接入 [CredentialGenerationInvalidator] 集合：
 * 备份恢复/移除身份时经 [TokenSessionLoginInvalidator] 显式推进 WebDAV
 * 凭据世代（联合候选 A2-5 与 PR #18 restore lease 的对齐点）。
 */
@Singleton
class WebDavCredentialGenerationInvalidator @Inject constructor(
    private val coordinator: WebDavCredentialCoordinator,
) : CredentialGenerationInvalidator {

    override suspend fun invalidateCredentialsGeneration(serverId: String) {
        coordinator.invalidate(serverId)
    }
}
