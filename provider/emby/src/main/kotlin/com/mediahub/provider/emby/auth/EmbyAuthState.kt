package com.mediahub.provider.emby.auth

import com.mediahub.model.MediaUser

/**
 * Emby 认证状态机（见 ADR-026）。
 *
 * 明确区分：未检查 / 恢复中 / 未登录 / 已登录 / 会话失效 / 暂时不可用。
 * 禁止用 Boolean isLoggedIn 承载全部状态。
 *
 * 注意："服务器当前离线" ≠ "Token 已失效"——只有确定 401/403 等认证失败才清会话；
 * Timeout / DNS / 5xx 保留本地会话（状态为 [EmbyAuthState.Error]）。
 */
sealed interface EmbyAuthState {
    data object Unknown : EmbyAuthState
    data object SignedOut : EmbyAuthState
    data object Restoring : EmbyAuthState
    data object Authenticating : EmbyAuthState
    data class Authenticated(val user: MediaUser) : EmbyAuthState
    data class Error(val kind: EmbyAuthErrorKind, val message: String) : EmbyAuthState
}

/** 认证错误分类（登录 / 验证 / 恢复共用）。 */
enum class EmbyAuthErrorKind {
    /** 用户名或密码错误（登录时的 401/403） */
    INVALID_CREDENTIALS,

    /** 已有会话失效（认证请求 401/403，token 已撤销） */
    SESSION_EXPIRED,

    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    SERVER_ERROR,
    INVALID_RESPONSE,
    PROTOCOL_MISMATCH,
    UNKNOWN,
}
