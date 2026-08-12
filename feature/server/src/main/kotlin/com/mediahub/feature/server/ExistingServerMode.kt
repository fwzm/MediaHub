package com.mediahub.feature.server

/**
 * 已有服务器"编辑/修复"模式（评审 Final Reconciliation Patch 3）。
 *
 * 明确区分两种语义，避免只用一个 boolean isReauthorizing 混用：
 * - [AUTH_RELOGIN]：认证型 Provider（Emby/Jellyfin 等）签名已失效 → 重新登录（复用原 serverId）。
 * - [LOCAL_REAUTHORIZE]：Local SAF 目录授权失效/缺失 → 重新授权目录。
 * - [NONE]：新建媒体源。
 */
enum class ExistingServerMode {
    NONE,
    AUTH_RELOGIN,
    LOCAL_REAUTHORIZE,
}
