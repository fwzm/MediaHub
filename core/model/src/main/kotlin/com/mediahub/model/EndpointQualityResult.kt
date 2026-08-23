package com.mediahub.model

/**
 * 线路质量测试结果（U4-D，独立于 Entity 的测试 DTO）。
 * 由 EndpointTestService 产出，经 Repository 落库到 ServerEndpointEntity。
 */
data class EndpointQualityResult(
    val endpointId: String,
    val apiLatencyMs: Long? = null,
    val mediaFirstByteMs: Long? = null,
    val downloadSpeedBytesPerSec: Long? = null,
    val httpStatus: Int? = null,
    val protocol: String? = null,
    val supportsRange: Boolean = false,
    val testedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
) {
    /** 播放质量评分（0-100）。 */
    fun score(): Int {
        if (error != null) return 0
        var score = 100
        apiLatencyMs?.let { if (it > 1000) score -= 20 }
        mediaFirstByteMs?.let { if (it > 3000) score -= 30 }
        downloadSpeedBytesPerSec?.let {
            if (it < 5L * 1024 * 1024) score -= 30 // <5 MB/s
        }
        if (!supportsRange) score -= 40
        return score.coerceIn(0, 100)
    }
}