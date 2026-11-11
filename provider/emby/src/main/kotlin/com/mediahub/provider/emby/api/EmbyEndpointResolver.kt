package com.mediahub.provider.emby.api

/**
 * Emby API 根路径解析（官方文档：`http[s]://hostname:port/emby/{apipath}`）。
 *
 * 用户输入的 baseUrl（如 `http://192.168.1.100:8096`）→ API base `http://.../emby`；
 * 若用户已输入 `/emby` 则不重复追加。
 */
class EmbyEndpointResolver(userBaseUrl: String) {

    private val apiBase: String = buildString {
        val base = userBaseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "Emby 服务器地址不能为空" }
        append(base)
        if (!endsWith("/emby")) append("/emby")
    }

    /** 根路径和 endpoint 之间保持一个分隔符，兼容有或无前导斜线的 path。 */
    fun endpoint(path: String): String = "$apiBase/${path.trimStart('/')}"
}
