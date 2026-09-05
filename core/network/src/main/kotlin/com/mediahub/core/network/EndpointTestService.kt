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
 * 1. API Test：GET {baseUrl + probePath}，测 DNS+TCP+TLS+HTTP 总延迟。
 * 2. Media Test：Range 1MB 模拟真实播放首包+吞吐。
 * 不下载完整视频；不记录 token/URL。
 *
 * Phase 1G-A（ADR-039）：[probePath] 由调用方从 ProviderDescriptor.probePath 传入——
 * 本类**不含任何 Emby/Jellyfin 协议路径知识**（/emby 前缀属于 Emby provider 自述）。
 *
 * open：Phase 1I 线路质量测试的陈旧结果隔离需要可控延迟的测试替身
 * （EmbyApiClient/EndpointTestService 同款 open-for-test 约定）。
 *
 * **已知风险（Phase 1I review 登记项，单独处理）**：[test] 内部使用同步 `Call.execute()`
 * 且本类未切换 IO dispatcher——suspend 声明不会自动把阻塞调用移出调用方调度器
 * （当前调用方在 viewModelScope 主调度器上）。测试暂以 fake-service 通过，不外推为
 * 真实线路测试的线程安全已验证；线程化改造另行处理。
 */
open class EndpointTestService(
    private val clientFactory: HttpClientFactory,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    open suspend fun test(baseUrl: String, probePath: String): EndpointTestResult {
        val probeUrl = baseUrl.trimEnd('/') + probePath
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
            val start = clock()
            val resp = client.newCall(
                Request.Builder().url(probeUrl).build()
            ).execute()
            apiLatency = clock() - start
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
                val start = clock()
                val request = Request.Builder()
                    .url(probeUrl) // placeholder, real impl uses a known item ID
                    .header("Range", "bytes=0-1048575")
                    .build()
                val resp = mediaClient.newCall(request).execute()
                mediaFirstByte = clock() - start
                rangeOk = resp.code == 206 || resp.header("Accept-Ranges") == "bytes"
                val body = resp.body
                if (body != null) {
                    val bytes = body.bytes().size.toLong()
                    val elapsedSec = (clock() - start) / 1000.0
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