package com.mediahub.provider.api

import com.mediahub.model.ServerType

/**
 * 图片鉴权贡献者（Phase 1G-A，ADR-039）：**基础设施，不是 feature capability**
 * （不进 ProviderHandle、不新增 ProviderCapability）。
 *
 * 各 Provider 为自己类型的 [com.mediahub.model.MediaServer] 生成图片请求认证头；
 * app 层只负责 known-origin → server 解析与按 [serverType] 分发，
 * **不得 import 任何具体 provider 模块**（EmbyImageAuthStore 的 EMBY-only 过滤由此废除）。
 *
 * - 同源注入 / 跨源剥离凭据由 core:network OriginScopedCredentialInterceptor 兜底（ADR-030）；
 * - Token 永不进图片 URL（ADR-026 同款红线）；
 * - 未登录 / 凭据缺失返回 null → 请求原样放行（服务端 401 / 占位图兜底）。
 */
interface ProviderImageAuthContributor {
    val serverType: ServerType

    /** 为指定本地服务器生成图片请求认证头；凭据缺失返回 null。 */
    suspend fun headersFor(serverId: String): Map<String, String>?
}
