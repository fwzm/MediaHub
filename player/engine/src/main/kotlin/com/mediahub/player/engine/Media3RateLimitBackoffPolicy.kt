package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/**
 * G5-b：Media3 取流路径的 **429 退避**。
 *
 * 背景：ADR-046 已裁定 Media3 DataSource 层只**记录不抑制** 429（冷却窗口内抛异常
 * 会把可恢复的限流变成播放硬中断，且续流 Range 请求会被误杀）。该裁定的已知局限是
 * "重试节奏仍由 Media3 默认策略决定"——默认策略不解析 `Retry-After`，会在窗口内
 * 按 1s/2s/4s 连续重打，回到**重试风暴**。
 *
 * 本类把退避放到 Media3 官方指定的上层落点 [LoadErrorHandlingPolicy]：
 * - 只在**加载失败后**决定下一次重试的等待时长，**不触碰已建立的数据流**，
 *   因此不会中断正在播放的内容（这正是 ADR-046 拒绝 DataSource 层抑制的原因）；
 * - 只对 `HTTP 429` 生效；其余状态码 / 普通 IO 失败 **逐字节委托**
 *   [DefaultLoadErrorHandlingPolicy]，行为与接入前完全一致；
 * - `Retry-After` 只解析 delta-seconds（Emby/Jellyfin 的实际形态）；
 *   缺失、非法或 HTTP-date 一律退化为保守默认值，避免把解析失败放大成风暴；
 * - 退避有**硬上限**（[DEFAULT_MAX_DELAY_MS]），429 重试有**次数上限**
 *   （[DEFAULT_MAX_ATTEMPTS]），用尽后返回 [C.TIME_UNSET] 停止重试，
 *   交给既有错误路径（用户可见的播放错误 / 源不可用），而不是无限等待。
 *
 * 说明：`maxAttempts = 2` 表示"首次失败后最多再试 1 次"，与 `LoadErrorInfo.errorCount`
 * 从 1 开始计数的语义一致。
 *
 * ## 条目 136：EXO 解析错误重试上限
 *
 * 内容识别失败（[ParserException] / [UnrecognizedInputFormatException]）与 429
 * 不同：重试**不会**改变内容形态。Media3 默认策略对媒体加载仍会重试若干次，
 * 于是"把 m3u8 当渐进式解析失败"会变成一轮轮重复拉流。
 * 本类给这类错误单独设**更小的次数预算**（[DEFAULT_MAX_PARSER_ATTEMPTS]），
 * 用尽后返回 [C.TIME_UNSET] 走既有错误路径；预算内仍按委托策略的节奏等待。
 */
@UnstableApi
class Media3RateLimitBackoffPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val defaultDelayMs: Long = DEFAULT_DELAY_MS,
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
    private val maxParserAttempts: Int = DEFAULT_MAX_PARSER_ATTEMPTS,
) : LoadErrorHandlingPolicy by delegate {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        val isRateLimited = exception is HttpDataSource.InvalidResponseCodeException &&
            exception.responseCode == HTTP_TOO_MANY_REQUESTS
        if (!isRateLimited) {
            if (isFormatParseFailure(exception)) {
                if (loadErrorInfo.errorCount >= maxParserAttempts) {
                    // 解析失败重试不会改变内容形态：预算用尽立即停止，交给错误路径。
                    return C.TIME_UNSET
                }
            }
            return delegate.getRetryDelayMsFor(loadErrorInfo)
        }
        if (loadErrorInfo.errorCount >= maxAttempts) {
            // 不再重试：Media3 的权威约定，避免把 429 变成无限重试风暴。
            return C.TIME_UNSET
        }
        val headerFields = (exception as HttpDataSource.InvalidResponseCodeException).headerFields
        val requestedMs = retryAfterMs(headerFields) ?: defaultDelayMs
        return requestedMs.coerceIn(0L, maxDelayMs)
    }

    /** 解析/格式识别失败（含被包装的 cause），与网络类错误区分。 */
    private fun isFormatParseFailure(exception: Throwable?): Boolean {
        var current = exception
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is ParserException || current is UnrecognizedInputFormatException) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * `Retry-After` 的 delta-seconds 解析；缺失或非数字返回 null（调用方退化到默认值）。
     * 超大值饱和到 [Long.MAX_VALUE] 而不是溢出成 0/负值——负值会被误读为"立即重试"。
     */
    private fun retryAfterMs(headerFields: Map<String, List<String>>): Long? {
        val raw = headerFields.entries
            .firstOrNull { it.key.equals(RETRY_AFTER_HEADER, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?: return null
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds <= 0L) return 0L
        return if (seconds >= Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
    }

    companion object {
        const val HTTP_TOO_MANY_REQUESTS: Int = 429

        /** 首次失败后最多再重试 1 次。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 2

        /**
         * 解析/格式识别失败的预算（同样"首次失败后最多再试 1 次"）。
         *
         * 取 2 而非直接 1：媒体清单解析失败偶发于一过性截断/损坏响应，
         * 留一次重试能吸收这类噪声；再失败则几乎必然是内容形态不匹配，
         * 继续重试只会制造同一轮拉流风暴。
         */
        const val DEFAULT_MAX_PARSER_ATTEMPTS: Int = 2

        /**
         * 单次退避上限（10s）。取值理由：既明显拉开重打间隔、避免 1s/2s/4s 风暴，
         * 又不会让用户面对接近"卡死"的长等待；超过上限的 `Retry-After` 说明服务端
         * 冷却窗口远长于播放器可等待范围，此时应尽早走错误路径而不是空等。
         */
        const val DEFAULT_MAX_DELAY_MS: Long = 10_000L

        /** 429 未给出可用 `Retry-After` 时的保守退避（与 Media3 默认基数同量级）。 */
        const val DEFAULT_DELAY_MS: Long = 1_000L

        private const val RETRY_AFTER_HEADER = "Retry-After"

        /** 只检查有限深度的 cause 链，避免异常图成环或过深。 */
        private const val MAX_CAUSE_DEPTH = 8
    }
}
