package com.mediahub.provider.emby.api

/**
 * Emby API 根路径解析（官方文档：`http[s]://hostname:port/emby/{apipath}`）。
 *
 * 用户输入的 baseUrl（如 `http://192.168.1.100:8096`）→ API base `http://.../emby`；
 * 若用户已输入 `/emby` 则不重复追加。
 */
class EmbyEndpointResolver(userBaseUrl: String) {

    private val apiBase: String = buildString {
        append(userBaseUrl.trimEnd('/'))
        if (!endsWith("/emby")) append("/emby")
    }

    /** 返回完整 endpoint（path 以 / 开头）。 */
    fun endpoint(path: String): String = apiBase + path
}
