package com.mediahub.provider.api

import com.mediahub.model.MediaUser

/**
 * 会话恢复状态（通用契约，ADR-026）。
 * 所有实现 MediaAuthProvider 的数据源（Emby/Jellyfin/WebDAV/云盘）走同一生命周期。
 *
 * 明确区分：未检查 / 恢复中 / 未登录 / 已登录 / 会话失效 / 服务器身份变更 / 暂时不可用。
 * 禁止用 Boolean isLoggedIn 承载全部状态。
 *
 * 关键原则：只有明确认证失效（401）才销毁凭据；
 * 403 / 5xx / 网络 / 协议异常保留本地会话（"服务器当前离线" ≠ "Token 已失效"）。
 */
sealed interface AuthSessionState {
    data object Unknown : AuthSessionState
    data object Restoring : AuthSessionState
    data object SignedOut : AuthSessionState
    data class Authenticated(val user: MediaUser) : AuthSessionState
    data class Error(val kind: AuthSessionErrorKind, val message: String) : AuthSessionState
}

/** 会话恢复错误分类。 */
enum class AuthSessionErrorKind {
    /** 401：AccessToken 已撤销 → 应清会话并重新登录 */
    SESSION_EXPIRED,

    /** 服务器身份与已保存会话不一致 → 绝不发送旧 Token */
    SERVER_MISMATCH,

    /** 403：无权限 → 保留会话 */
    FORBIDDEN,

    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    SERVER_ERROR,
    INVALID_RESPONSE,
    UNKNOWN,
}
