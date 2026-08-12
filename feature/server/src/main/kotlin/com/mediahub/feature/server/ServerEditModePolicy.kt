package com.mediahub.feature.server

import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderCategory

/**
 * Existing-server 修复模式判定（评审 Final Reconciliation Patch 3）。
 *
 * 纯函数、无 Android 依赖，便于 JVM 单测。
 * 明确区分 AUTH_RELOGIN（认证型 Provider）与 LOCAL_REAUTHORIZE（本地目录），
 * 而非只用一个 boolean。
 */
internal object ServerEditModePolicy {

    /**
     * 根据原服务器的 descriptor 判断修复模式。
     * - [ProviderCategory.LOCAL_STORAGE] → LOCAL_REAUTHORIZE
     * - 其他（认证型，如 Emby/Jellyfin）→ AUTH_RELOGIN
     */
    fun modeFor(descriptor: ProviderDescriptor): ExistingServerMode =
        if (descriptor.category == ProviderCategory.LOCAL_STORAGE) {
            ExistingServerMode.LOCAL_REAUTHORIZE
        } else {
            ExistingServerMode.AUTH_RELOGIN
        }
}
