package com.mediahub.provider.api

import com.mediahub.model.ServerType

/**
 * 图片鉴权贡献者（Phase 1G-A，ADR-039）：**基础设施，不是 feature capability**
 * （不进 ProviderHandle、不新增 ProviderCapability）。
 *
 * 各 Provider 为自己类型的 [com.mediahub.model.MediaServer] 生成图片请求认证头；
 * app 层只做 **auth-scope → server** 归属与按 [serverType] 分发，
 * **不得 import 任何具体 provider 模块**（EmbyImageAuthStore 的 EMBY-only 过滤由此废除）。
 *
 * - [authScopeUrl]：Provider 自述认证 scope（由 baseUrl 推导，app 不理解协议前缀）。
 *   同 origin 可共存多个 scope（如 Emby `https://h/emby` + Jellyfin `https://h/jellyfin`）；
 *   app 按 path-segment boundary 最长前缀匹配归属，**同 scope 多 serverId 时 fail closed**
 *   （返回 null，绝不随机归属凭据——source/session-scoped credential 隔离）。
 * - 同源注入 / 跨源剥离凭据由 core:network OriginScopedCredentialInterceptor 兜底（ADR-030）；
 * - Token 永不进图片 URL（ADR-026 同款红线）；
 * - 未登录 / 凭据缺失返回 null → 请求原样放行（服务端 401 / 占位图兜底）。
 */
interface ProviderImageAuthContributor {
    val serverType: ServerType

    /** 由服务器 baseUrl 推导认证 scope URL（幂等，禁止破坏用户已配置的反代子路径）。 */
    fun authScopeUrl(baseUrl: String): String

    /** 为指定本地服务器生成图片请求认证头；凭据缺失返回 null。 */
    suspend fun headersFor(serverId: String): Map<String, String>?
}
