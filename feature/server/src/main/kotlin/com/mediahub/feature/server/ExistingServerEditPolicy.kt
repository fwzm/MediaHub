package com.mediahub.feature.server

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.model.MediaItem
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.ProviderDescriptor

/**
 * Existing-server re-login / reauthorization 的编辑策略（评审 FINAL PATCH 2）。
 *
 * 纯函数、无 Android 依赖，便于 JVM 单测。
 *
 * 核心约束：
 * - descriptor 按 [ServerType] 匹配（禁止用 enum.name 当 ProviderDescriptor.id —— "EMBY" ≠ "emby"）。
 * - existing 模式下 Provider 类型锁定原 [existing.type]（不允许 UI 切到别的 Provider）。
 * - needsRelogin 精确：仅 SignedOut / SESSION_EXPIRED / SERVER_MISMATCH 进入重登录；
 *   FORBIDDEN / 网络 / 5xx / INVALID_RESPONSE 保留 session（不送重登录页）。
 * - 更新时完整保留元数据：id/type/isDefault/sortOrder/createdAtEpochMs/lastConnectedAtEpochMs/lastError。
 */
internal object ExistingServerEditPolicy {

    data class Draft(
        val server: MediaServer,
        val updateSource: Boolean,
        val providerLocked: Boolean,
    )

    /** 按 serverType 找 descriptor（不匹配则返回 null，调用方应显示错误而非静默）。 */
    fun descriptorFor(server: MediaServer, descriptors: List<ProviderDescriptor>): ProviderDescriptor? =
        descriptors.firstOrNull { it.serverType == server.type }

    /** existing 模式是否锁定原 Provider。 */
    fun isProviderLocked(existing: MediaServer?): Boolean = existing != null

    /**
     * 判定卡片点击是否进入"重新登录"（评审精确语义）。
     */
    fun needsRelogin(server: MediaServer, authState: AuthSessionState?, isAuthProvider: Boolean): Boolean {
        if (!isAuthProvider) return false
        return when (authState) {
            is AuthSessionState.Authenticated -> false
            is AuthSessionState.Error -> when (authState.kind) {
                AuthSessionErrorKind.SESSION_EXPIRED, AuthSessionErrorKind.SERVER_MISMATCH -> true
                AuthSessionErrorKind.FORBIDDEN,
                AuthSessionErrorKind.NETWORK_TIMEOUT,
                AuthSessionErrorKind.NETWORK_UNAVAILABLE,
                AuthSessionErrorKind.SERVER_ERROR,
                AuthSessionErrorKind.INVALID_RESPONSE,
                AuthSessionErrorKind.UNKNOWN,
                -> false
            }

            AuthSessionState.SignedOut -> true
            else -> false // Unknown / Restoring
        }
    }

    /**
     * 构造待保存的服务器草稿。
     * - [existing] 非空：复用原 id，保留全部元数据，仅允许编辑 name/baseUrl/username（updateServer）。
     * - [existing] 为空：新建（addServer），按候选字段创建。
     */
    fun buildDraft(
        existing: MediaServer?,
        candidate: MediaServer,
    ): Draft {
        return if (existing != null) {
            val edited = candidate.copy(
                id = existing.id,
                type = existing.type,
                isDefault = existing.isDefault,
                sortOrder = existing.sortOrder,
                createdAtEpochMs = existing.createdAtEpochMs,
                lastConnectedAtEpochMs = existing.lastConnectedAtEpochMs,
                lastError = existing.lastError,
            )
            Draft(server = edited, updateSource = true, providerLocked = true)
        } else {
            Draft(server = candidate, updateSource = false, providerLocked = false)
        }
    }
}
