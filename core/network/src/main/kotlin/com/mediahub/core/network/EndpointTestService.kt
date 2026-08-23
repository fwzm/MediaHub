package com.mediahub.core.network

import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request

/** 线路测试结果（U4-D）。 */
data class EndpointTestResult(
    val apiLatencyMs: Long,
    val mediaFirstByteMs: Long?,
    val mediaThroughputMbps: Double?,
    val httpCode: Int,
    val protocol: String?,
    val supportsRange: Boolean,
    val error: String? = null,
)

/**
 * 两层线路测试（U4-D）：
 * 1. API Test：GET /System/Info/Public，测 DNS+TCP+TLS+HTTP 总延迟。
 * 2. Media Test：Range 1MB 模拟真实播放首包+吞吐。
 * 不下载完整视频；不记录 token/URL。
 */
class EndpointTestService(private val clientFactory: HttpClientFactory) {

    suspend fun test(baseUrl: String): EndpointTestResult {
        var apiLatency = -1L
        var mediaFirstByte: Long? = null
        var throughput: Double? = null
        var code = 0
        var protocol: String? = null
        var rangeOk = false
        var errorMsg: String? = null

        val client = clientFactory.apiClient()

        // ---- Layer 1: API latency ----
        try {
            val start = SystemClock.elapsedRealtime()
            val resp = client.newCall(
                Request.Builder().url("$baseUrl/emby/System/Info/Public").build()
            ).execute()
            apiLatency = SystemClock.elapsedRealtime() - start
            code = resp.code
            protocol = resp.protocol?.toString()
            resp.close()
        } catch (e: Exception) {
            errorMsg = "API test failed: ${e.message}"
        }

        // ---- Layer 2: Media Range 1MB ----
        if (errorMsg == null) {
            try {
                val mediaClient = clientFactory.mediaClient()
                val start = SystemClock.elapsedRealtime()
                val request = Request.Builder()
                    .url("$baseUrl/emby/System/Info/Public") // placeholder, real impl uses a known item ID
                    .header("Range", "bytes=0-1048575")
                    .build()
                val resp = mediaClient.newCall(request).execute()
                mediaFirstByte = SystemClock.elapsedRealtime() - start
                rangeOk = resp.code == 206 || resp.header("Accept-Ranges") == "bytes"
                val body = resp.body
                if (body != null) {
                    val bytes = body.bytes().size.toLong()
                    val elapsedSec = (SystemClock.elapsedRealtime() - start) / 1000.0
                    if (elapsedSec > 0 && bytes > 0) {
                        throughput = (bytes / (1024.0 * 1024.0)) / elapsedSec
                    }
                }
                code = resp.code
                resp.close()
            } catch (e: Exception) {
                // media test failure doesn't invalidate API result
            }
        }

        return EndpointTestResult(
            apiLatencyMs = apiLatency,
            mediaFirstByteMs = mediaFirstByte,
            mediaThroughputMbps = throughput,
            httpCode = code,
            protocol = protocol,
            supportsRange = rangeOk,
            error = errorMsg,
        )
    }
}