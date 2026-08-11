package com.mediahub.model

/** 账号认证状态。 */
enum class AuthState {
    /** 未认证 / 未登录 */
    NONE,

    /** 认证中（异步进行） */
    PENDING,

    /** 认证成功 */
    AUTHENTICATED,

    /** 凭据过期，需要重新登录或刷新 */
    EXPIRED,

    /** 认证失败 */
    ERROR
}
