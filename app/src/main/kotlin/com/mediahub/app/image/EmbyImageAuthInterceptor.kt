package com.mediahub.app.image

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 图片请求鉴权拦截器（Phase 1B-2.3）：命中已知 Emby origin 的图片请求注入
 * X-Emby-Token / X-Emby-Authorization（ADR-026：Token 只走 Header，永不进 URL）。
 *
 * - 依赖以函数注入（[headersFor]），纯 JVM 可测（不需要 Room/Keystore）；
 * - application interceptor 只作用于首请求；跨 origin 重定向的剥离由
 *   core/network 的 OriginScopedCredentialInterceptor（network 层）兜底（ADR-030）。
 */
class EmbyImageAuthInterceptor(
    private val headersFor: (HttpUrl) -> Map<String, String>?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = headersFor(request.url) ?: return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}
