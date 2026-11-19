package com.mediahub.feature.player

import com.mediahub.provider.api.ProviderException
import java.io.IOException

/**
 * 播放解析链路的瞬时错误重试策略。
 *
 * 只对网络类/服务端临时错误重试一次：断网恢复、连接被拒、5xx 等场景；
 * 鉴权、资源不存在、解析失败、未实现等确定性错误立即失败，避免放大故障。
 */
internal object PlaybackResolveRetryPolicy {
    /** 首次尝试 + 一次重试。 */
    const val MAX_ATTEMPTS: Int = 2
    const val RETRY_DELAY_MS: Long = 1_000L

    fun shouldRetry(error: Throwable, attempt: Int): Boolean {
        if (attempt >= MAX_ATTEMPTS - 1) return false
        return when (error) {
            is ProviderException -> when (error.code) {
                ProviderException.ErrorCode.NETWORK,
                ProviderException.ErrorCode.CONNECTION -> true

                ProviderException.ErrorCode.HTTP ->
                    (error as? ProviderException.Http)?.statusCode?.let { it >= 500 } == true

                else -> false
            }

            is IOException -> true
            else -> false
        }
    }
}
