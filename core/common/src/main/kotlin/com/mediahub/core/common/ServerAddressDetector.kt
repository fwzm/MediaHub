package com.mediahub.core.common

/**
 * 服务器协议/端口自动识别（审计条目 75）。
 *
 * 现状（修复前）：裸主机只按 `preferHttps` 补 scheme，端口沿用协议默认值，
 * 于是 `media.example` 永远连 `https://media.example`（443），而 Emby 的真实端口
 * 通常是 `8096`（http）或 `8920`（https）——用户必须手工补端口。
 *
 * [plan] 只做**纯计算**（不联网），[detect] 由调用方注入探测器（feature:server 用
 * `HttpURLConnection`），便于 JVM 单测与"不发真实网络"的测试约束。
 *
 * 规则：
 * - 显式 scheme + 显式端口 → 唯一候选（用户输入为准，不擅自换端口）；
 * - 显式 scheme 无端口 → **只**探测该 scheme 的常见端口（不擅自跨协议降级/升级）；
 * - 无 scheme 无端口 → 按 [preferHttps] 决定先 https 还是先 http，两协议常见端口全覆盖；
 * - 端口与 scheme 严格配对（绝不生成 `http:8920` / `https:8096`）；
 * - 候选数上限 [MAX_CANDIDATES]、按 URL 去重；非法输入不产生候选（由
 *   [ServerAddressNormalizer] 报字段错误，探测不得吞掉错误）。
 */
object ServerAddressDetector {

    private const val HTTP = "http"
    private const val HTTPS = "https"

    /** Emby 默认端口（http）与 Jellyfin/Emby 常见 http 端口。 */
    val HTTP_PORTS: List<Int> = listOf(8096, 80)

    /** Emby/Jellyfin 常见 https 端口。 */
    val HTTPS_PORTS: List<Int> = listOf(8920, 443)

    /** 单次自动识别最多探测的候选数（防止误输入触发请求放大）。 */
    const val MAX_CANDIDATES: Int = 6

    /** 一个待探测候选；[scheme] 与 [port] 始终匹配。 */
    data class Candidate(val url: String, val scheme: String, val port: Int)

    sealed interface DetectionResult {
        /** [attempted] 含成功命中的那一个（按尝试顺序）。 */
        data class Detected(
            val address: String,
            val scheme: String,
            val port: Int,
            val attempted: List<String>,
        ) : DetectionResult

        /** 全部候选都不可达；[attempted] 为真实尝试过的地址。 */
        data class NotDetected(val attempted: List<String>) : DetectionResult
    }

    /**
     * 生成候选顺序（确定性：同一输入永远同一顺序）。
     * 输入非法（[ServerAddressNormalizer] 判定 Invalid）时返回空列表。
     */
    fun plan(input: String, preferHttps: Boolean): List<Candidate> {
        val address = when (val result = ServerAddressNormalizer.normalize(input, preferHttps)) {
            is ServerAddressNormalizer.Result.Invalid -> return emptyList()
            is ServerAddressNormalizer.Result.Ok -> result.address
        }
        // 显式端口：尊重用户输入，唯一候选
        address.port?.let { return listOf(Candidate(address.url, address.scheme, it)) }

        val explicitScheme = ServerAddressNormalizer.explicitHttpScheme(input) != null
        val schemes = when {
            explicitScheme -> listOf(address.scheme)
            preferHttps -> listOf(HTTPS, HTTP)
            else -> listOf(HTTP, HTTPS)
        }
        val candidates = LinkedHashMap<String, Candidate>()
        for (scheme in schemes) {
            for (port in portsFor(scheme)) {
                val url = "$scheme://${address.host}:$port${address.path}"
                if (candidates.size >= MAX_CANDIDATES) break
                candidates.putIfAbsent(url, Candidate(url, scheme, port))
            }
            if (candidates.size >= MAX_CANDIDATES) break
        }
        return candidates.values.toList()
    }

    /**
     * 按顺序真实探测：第一个"能建立 HTTP 响应"的候选胜出。
     * [prober] 返回 true 表示该地址可达（含 401/403/404 —— 有响应即协议/端口正确），
     * 抛出的异常视为不可达。
     */
    suspend fun detect(
        candidates: List<Candidate>,
        prober: suspend (String) -> Boolean,
    ): DetectionResult {
        val attempted = mutableListOf<String>()
        for (candidate in candidates) {
            attempted += candidate.url
            val reachable = runCatching { prober(candidate.url) }.getOrDefault(false)
            if (reachable) {
                return DetectionResult.Detected(
                    address = candidate.url,
                    scheme = candidate.scheme,
                    port = candidate.port,
                    attempted = attempted.toList(),
                )
            }
        }
        return DetectionResult.NotDetected(attempted.toList())
    }

    private fun portsFor(scheme: String): List<Int> = if (scheme == HTTPS) HTTPS_PORTS else HTTP_PORTS
}
