package com.mediahub.player.engine

import com.mediahub.core.network.PlaybackError

/**
 * 播放失败归类（缺陷 47 / 87）。
 *
 * Media3 没有为"共享纹理/SurfaceTexture 创建失败"提供稳定的错误码，
 * 设备厂商通常把原因放在异常链的 message 里。这里只做**字符串取证归类**，
 * 不改变原有错误码优先级：能识别的表面/数据访问错误走专用恢复路径，
 * 其余错误仍按原逻辑处理。
 */
object PlaybackFailureClassifier {

    private val SURFACE_MARKERS = listOf(
        "shared texture",
        "surfacetexture",
        "surface texture",
        "create texture",
        "dequeuebuffer",
        "eglcreate",
        "gralloc",
        "共享纹理",
    )

    private val DATA_ACCESS_MARKERS = listOf(
        "permission denied",
        "eacces",
        "securityexception",
        "no such file",
        "filenotfound",
        "open failed",
        "无法访问",
    )

    fun classify(message: String?): PlaybackError.Code? {
        val normalized = message?.lowercase() ?: return null
        if (normalized.isBlank()) return null
        if (SURFACE_MARKERS.any { normalized.contains(it) }) return PlaybackError.Code.SURFACE_ERROR
        if (DATA_ACCESS_MARKERS.any { normalized.contains(it) }) return PlaybackError.Code.DATA_ACCESS_ERROR
        return null
    }

    /** 把异常自身及 cause 链的 message 拼起来，避免厂商只把原因放在深层 cause。 */
    fun classify(throwable: Throwable): PlaybackError.Code? =
        classify(
            generateSequence(throwable) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | "),
        )
}
