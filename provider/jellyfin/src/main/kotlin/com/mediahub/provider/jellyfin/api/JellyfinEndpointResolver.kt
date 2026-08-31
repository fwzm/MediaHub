package com.mediahub.provider.jellyfin.api

/**
 * Jellyfin endpoint 解析：baseUrl **原样承载反代子路径**（如 `https://host/jellyfin`），
 * path 直接追加——本类不含任何协议前缀知识（Emby 的 `/emby` 前缀属于 Emby provider，
 * 禁止出现在 Jellyfin 连接面，ADR-039）。
 */
class JellyfinEndpointResolver(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    fun endpoint(path: String): String = base + path
}
