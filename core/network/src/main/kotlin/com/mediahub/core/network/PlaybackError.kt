package com.mediahub.core.network

import com.mediahub.core.logging.Redactor

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 结构化播放错误。UI 展示用户可理解的文案，
 * 开发诊断信息见 [details] 与日志（已脱敏）。
 */
class PlaybackError(
    val code: Code,
    message: String? = null,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : Exception(message ?: code.defaultMessage, cause) {

    enum class Code(val defaultMessage: String) {
        AUTH_EXPIRED("登录状态已失效，请重新登录"),
        URL_EXPIRED("播放链接已过期，正在重新解析"),
        HTTP_403("服务器拒绝访问（403），登录状态可能已失效"),
        HTTP_404("媒体文件不存在（404）"),
        HTTP_429("请求过于频繁（429），请稍后重试"),
        SERVER_ERROR("服务器错误（5xx）"),
        NETWORK_TIMEOUT("网络超时"),
        DNS_ERROR("无法解析服务器地址"),
        TLS_ERROR("TLS 连接失败，请检查服务器证书"),
        DECODER_ERROR("解码器错误"),
        UNSUPPORTED_CODEC("当前设备不支持该编码格式"),
        DRM_ERROR("DRM 错误"),
        TRANSCODE_ERROR("服务器转码失败"),
        UNKNOWN("未知播放错误"),
    }

    /** 便于日志的结构化摘要（已脱敏）。 */
    fun toLogString(): String =
        "PlaybackError{code=${code.name}, details=${Redactor.redact(details.toString())}, cause=${cause?.javaClass?.simpleName}}"
}

/** HTTP 状态码 / IO 异常 → [PlaybackError] 的纯函数映射（可单测）。 */
object PlaybackErrorMapper {

    fun fromHttpStatus(status: Int, url: String): PlaybackError {
        val details = mapOf("status" to status.toString(), "url" to url)
        return when (status) {
            401 -> PlaybackError(PlaybackError.Code.AUTH_EXPIRED, details = details)
            403 -> PlaybackError(PlaybackError.Code.HTTP_403, details = details)
            404 -> PlaybackError(PlaybackError.Code.HTTP_404, details = details)
            429 -> PlaybackError(PlaybackError.Code.HTTP_429, details = details)
            in 500..599 -> PlaybackError(PlaybackError.Code.SERVER_ERROR, details = details)
            else -> PlaybackError(PlaybackError.Code.UNKNOWN, details = details)
        }
    }

    fun fromIoException(e: IOException): PlaybackError = when (e) {
        is SocketTimeoutException -> PlaybackError(PlaybackError.Code.NETWORK_TIMEOUT, cause = e)
        is UnknownHostException -> PlaybackError(PlaybackError.Code.DNS_ERROR, cause = e)
        is SSLException -> PlaybackError(PlaybackError.Code.TLS_ERROR, cause = e)
        else -> PlaybackError(PlaybackError.Code.UNKNOWN, cause = e)
    }

    fun fromHttpStatusOrIo(status: Int?, e: IOException?, url: String): PlaybackError =
        when {
            status != null -> fromHttpStatus(status, url)
            e != null -> fromIoException(e)
            else -> PlaybackError(PlaybackError.Code.UNKNOWN)
        }
}
