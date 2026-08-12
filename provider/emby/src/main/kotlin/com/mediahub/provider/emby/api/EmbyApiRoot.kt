package com.mediahub.provider.emby.api

/**
 * Emby API 根路径规范化。用户可填写服务器根地址或已带 `/emby` 的反代路径；
 * 所有 endpoint 只能通过这里拼接，避免 `/emby/emby` 或模块间根路径不一致。
 */
@JvmInline
value class EmbyApiRoot private constructor(val value: String) {
    fun endpoint(path: String): String = "$value/${path.trimStart('/')}"

    companion object {
        fun from(rawBaseUrl: String): EmbyApiRoot {
            val normalized = rawBaseUrl.trim().trimEnd('/')
            require(normalized.isNotBlank()) { "Emby 服务器地址不能为空" }
            return EmbyApiRoot(normalized)
        }
    }
}
