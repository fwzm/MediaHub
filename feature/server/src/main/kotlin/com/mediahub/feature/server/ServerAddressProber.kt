package com.mediahub.feature.server

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认地址探测器（审计条目 75）：`GET` 候选根路径，**收到任何 HTTP 响应码即视为可达**
 * （401/403/404 都说明协议与端口是对的；只有"连不上"才算不可达）。
 *
 * 用 `java.net.HttpURLConnection`（不引入新依赖），超时各 2s，跟随重定向。
 * 异常（DNS/拒连/超时/TLS）一律 false —— 自动识别是**尽力而为**，不得把
 * 探测异常抛给 UI。
 */
internal object ServerAddressProber {

    private const val TIMEOUT_MS = 2_000

    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection() as? HttpURLConnection ?: return@withContext false
            connection = opened
            opened.requestMethod = "GET"
            opened.connectTimeout = TIMEOUT_MS
            opened.readTimeout = TIMEOUT_MS
            opened.instanceFollowRedirects = true
            opened.setRequestProperty("Accept", "*/*")
            opened.responseCode > 0
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
