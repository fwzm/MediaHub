package com.mediahub.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * G5 · **只记录不抑制** 的 429 观测拦截器（record-only）。
 *
 * 为什么不是 [RateLimitCooldownInterceptor]：
 * 冷却拦截器会在窗口内**抛异常快速失败**。对 API 链（幂等、可重试、失败可提示用户）
 * 这是正确的；但对**播放取流**与**图片加载**是错的 ——
 * 把可恢复的限流变成播放硬中断 / 图片整片不显示，用户看到的是「播放器坏了」。
 *
 * 因此本拦截器只做一件事：把 429 写进 [NetworkExchangeLedger]，使限流**可见**，
 * 同时**逐字节保持**原有请求-响应语义（不重试、不等待、不改响应）。
 *
 * 契约（由 `RateLimitRecordOnlyInterceptorTest` 固定）：
 * 1. 429 响应**原样返回**给调用方（不吞、不等、不重试）；
 * 2. 台账的 429 计数**增加**；
 * 3. 后续同源请求**照样出网**（绝不抑制）—— 与本拦截器接入前行为一致。
 *
 * 已知局限（写进 ADR，不隐瞒）：本拦截器**不能**阻止重试风暴，只能让它可见。
 * 真正的退避应在上层（Media3 的 LoadErrorHandlingPolicy / Coil 的 retry 策略）做，
 * 属后续切片。
 */
class RateLimitRecordOnlyInterceptor(
    private val ledger: NetworkExchangeLedger,
    /** 解析 URL 所属服务器；未知返回 null（此时回退到精确 origin 以免跨服混淆）。 */
    private val scopeOf: (okhttp3.HttpUrl) -> String? = { null },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            // 只记账。**绝不** throw、**绝不** sleep、**绝不**重试。
            ledger.recordStatus(429)
        }
        return response
    }
}
