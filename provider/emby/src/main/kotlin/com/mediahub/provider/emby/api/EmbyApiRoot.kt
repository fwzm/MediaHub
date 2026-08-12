package com.mediahub.provider.emby.api

/**
 * Emby API 根路径规范化（官方总文档：`http[s]://hostname:port/emby/{apipath}`）。
 *
 * 用户可填写服务器根地址（如 `http://192.168.1.100:8096`）或已带 `/emby` 的反代路径；
 * [from] 统一追加 `/emby` 前缀（已含 /emby 则不重复），所有 endpoint 只能经 [endpoint] 拼接。
 */
@JvmInline
value class EmbyApiRoot private constructor(val value: String) {

    fun endpoint(path: String): String = "$value/${path.trimStart('/')}"

    companion object {
        private const val API_PREFIX = "/emby"

        fun from(rawBaseUrl: String): EmbyApiRoot {
            val trimmed = rawBaseUrl.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Emby 服务器地址不能为空" }
            val withPrefix = if (trimmed.endsWith(API_PREFIX)) trimmed else trimmed + API_PREFIX
            return EmbyApiRoot(withPrefix)
        }
    }
}
