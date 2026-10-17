package com.mediahub.app.net

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL → serverId 归属索引（**M0-A3 真机缺陷修复**）。
 *
 * 背景：UA 归属最初复用图片鉴权作用域 [com.mediahub.app.image.ProviderImageAuthStore]，
 * 而该作用域只对**具有图片鉴权 contributor 的服务器类型**建立（Emby / Jellyfin）。
 * 真机实测：WebDAV 媒体源的出网请求因此解析不到 serverId → 自定义 UA **不生效**
 * （请求仍带默认 UA），而配置本身已正确落盘。
 *
 * 本类按**服务器表**（所有类型）建立归属索引，只服务 UA 归属：
 * - 与凭据/图片鉴权**完全解耦**，不改变 ADR-030/031 的凭据语义；
 * - 匹配规则与图片鉴权保持一致：同 scheme/host/port + **最长 path 前缀**；
 * - 同前缀命中多个 serverId → 返回 null（fail closed），交由调用方回落默认 UA。
 */
@Singleton
class ServerUrlIndex @Inject constructor(
    private val serverStore: ServerStore,
) {
    internal data class Entry(
        val scheme: String,
        val host: String,
        val port: Int,
        val segments: List<String>,
        val serverId: String,
    )

    @Volatile
    internal var entries: List<Entry> = emptyList()
        private set

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { serverStore.observeServers().collect { onServersChanged(it) } }
        }
    }

    /** internal 供 JVM 单测确定性驱动（与 ProviderImageAuthStore 同一模式）。 */
    internal fun onServersChanged(servers: List<MediaServer>) {
        entries = servers.flatMap { server ->
            server.endpoints.mapNotNull { endpoint ->
                val url = endpoint.url.toHttpUrlOrNull() ?: return@mapNotNull null
                Entry(
                    scheme = url.scheme,
                    host = url.host,
                    port = url.port,
                    segments = url.encodedPath.split('/').filter { it.isNotEmpty() },
                    serverId = server.id,
                )
            }
        }
    }

    /** 唯一归属的 serverId；无法唯一归属返回 null（fail closed）。 */
    fun serverIdFor(url: HttpUrl): String? {
        val requestSegments = url.encodedPath.split('/').filter { it.isNotEmpty() }
        val matched = entries.filter {
            it.scheme == url.scheme &&
                it.host == url.host &&
                it.port == url.port &&
                isPathPrefix(requestSegments, it.segments)
        }
        if (matched.isEmpty()) return null
        val bestLength = matched.maxOf { it.segments.size }
        val best = matched.filter { it.segments.size == bestLength }
        return if (best.map { it.serverId }.distinct().size == 1) best.first().serverId else null
    }

    private fun isPathPrefix(request: List<String>, scope: List<String>): Boolean =
        scope.size <= request.size && request.take(scope.size) == scope
}
