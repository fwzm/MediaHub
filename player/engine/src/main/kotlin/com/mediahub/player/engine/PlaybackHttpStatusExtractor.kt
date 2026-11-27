@file:OptIn(UnstableApi::class)

package com.mediahub.player.engine

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.mediahub.core.network.PlaybackError
import com.mediahub.core.network.PlaybackErrorMapper

/**
 * Media3 `ERROR_CODE_IO_BAD_HTTP_STATUS` 的状态码解包（审计条目 22）。
 *
 * 背景：ExoPlayer 对 403/404/429/5xx 只在 `PlaybackException.cause` 里携带
 * `HttpDataSource.InvalidResponseCodeException`（含 `responseCode` 与 `dataSpec.uri`），
 * 此前 [PlaybackEngine] 的映射只认解码器/DRM/网络超时，其余一律 UNKNOWN ——
 * 用户看到"未知播放错误"，无法判断是权限、地址还是限流。
 *
 * 取不到状态码时返回 null（调用方保持 UNKNOWN，**不猜**）。
 */
object PlaybackHttpStatusExtractor {

    data class HttpStatus(val statusCode: Int, val uri: String)

    fun extract(errorCode: Int, cause: Throwable?): HttpStatus? {
        if (errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return null
        var current = cause
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return HttpStatus(current.responseCode, current.dataSpec.uri.toString())
            }
            current = current.cause
        }
        return null
    }
}

/** [PlaybackException] → 结构化 [PlaybackError]（先解包 HTTP 状态码，再按错误类别兜底）。 */
internal fun mapPlaybackException(e: PlaybackException): PlaybackError {
    PlaybackHttpStatusExtractor.extract(e.errorCode, e.cause)?.let { http ->
        return PlaybackErrorMapper.fromHttpStatus(http.statusCode, http.uri)
    }
    val details = mapOf(
        "media3ErrorCode" to e.errorCode.toString(),
        "message" to (e.message ?: ""),
    )
    // 缺陷 47/87：厂商把「共享纹理失败 / 数据访问失败」塞在异常链 message 里，Media3 没有稳定错误码；
    // 先按取证字符串归类（SURFACE_ERROR / DATA_ACCESS_ERROR），识别不了再走下面的错误码映射。
    PlaybackFailureClassifier.classify(e)?.let { code ->
        return PlaybackError(code, details = details, cause = e)
    }
    return when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        -> PlaybackError(PlaybackError.Code.DECODER_ERROR, details = details, cause = e)

        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        -> PlaybackError(PlaybackError.Code.DRM_ERROR, details = details, cause = e)

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        -> PlaybackError(PlaybackError.Code.NETWORK_TIMEOUT, details = details, cause = e)

        else -> PlaybackError(PlaybackError.Code.UNKNOWN, details = details, cause = e)
    }
}
