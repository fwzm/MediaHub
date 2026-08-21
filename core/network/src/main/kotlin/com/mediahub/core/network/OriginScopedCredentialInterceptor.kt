package com.mediahub.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 跨 origin 重定向凭据隔离（Phase 1B-2.2，ADR-030）。
 *
 * 背景：Emby Direct Stream 常见链路是 `HTTPS(Emby) → 307 → HTTP 直链 → 对象存储`，
 * 播放必须跟随跨协议/跨域重定向（否则 Source error 2004）。但 media3 1.5.1 的
 * `DefaultHttpDataSource` 在手动 redirect 循环中会把 `DataSpec.httpRequestHeaders`
 * 原样重复发送给每一跳目标，无任何按 origin 的剥离逻辑——已用双 MockWebServer
 * 回归测试复现：X-Emby-Token 被转发给重定向后的第三方主机（明文 HTTP）。
 *
 * 修复：改用 OkHttp 数据源（redirect 由 OkHttp 原生跟随），本 network interceptor
 * 对每一跳生效（OkHttp 的 network interceptor 在每次重定向/重试请求都会经过），
 * 当请求 origin（scheme+host+port）与本次播放的首个请求 origin 不同时，剥离
 * 长期鉴权凭据头：
 * - 首跳（原 Emby origin）：X-Emby-Token / X-Emby-Authorization 照常携带；
 * - 跨 origin 跳（直链 IP / 对象存储）：上述凭据与 Authorization / Cookie 一律剥离；
 * - Range / User-Agent 等安全媒体头继续透传；
 * - RequiredHttpHeaders 是服务端显式指定给该媒体源的请求头，语义上就是发给
 *   目标主机的，按原样透传（更细的作用域设计留待后续阶段，当前无真实样本）。
 *
 * origin 基准取 `chain.call().request()`：ExoPlayer 每次 open（起播/seek/恢复）
 * 都以原始 Direct Stream URL 发起新的 call，因此基准始终是 Emby origin。
 */
class OriginScopedCredentialInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = chain.call().request().url
        val sameOrigin = request.url.scheme == original.scheme &&
            request.url.host == original.host &&
            request.url.port == original.port
        if (sameOrigin) {
            return chain.proceed(request)
        }
        val sanitized = request.newBuilder().apply {
            for (header in SENSITIVE_HEADERS) {
                removeHeader(header)
            }
        }.build()
        return chain.proceed(sanitized)
    }

    private companion object {
        /** 仅允许发送给原始 origin 的身份凭据头（OkHttp 按名移除，大小写不敏感）。 */
        val SENSITIVE_HEADERS = listOf(
            "X-Emby-Token",
            "X-MediaBrowser-Token",
            "X-Emby-Authorization",
            "X-MediaBrowser-Authorization",
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
        )
    }
}
