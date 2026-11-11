package com.mediahub.core.network

/**
 * 出网通道分类（M16-S0）。
 *
 * 分类的意义在于**不能把不同性质的流量混成一个数**：
 * 回环桥的下游接入是本机 mpv 连到本桥的连接，不是本设备对外的真实出网；
 * 本地文件读取根本不是网络请求。
 */
enum class RequestChannel {
    /** Provider/API 请求（Emby / Jellyfin / WebDAV 等）。 */
    API,
    /** 媒体字节流（Media3 DataSource / 直链播放）。 */
    MEDIA,
    /** 图片加载（Coil）。 */
    IMAGE,
    /** 字幕下载。 */
    SUBTITLE,
    /** mpv 回环桥**发往上游**的那一次请求（真实出网）。 */
    BRIDGE_UPSTREAM,
}

/**
 * 单次逻辑请求的**不可变**观测（M16-S0）。
 *
 * 隐私红线（V8 §三）：本类型**刻意不携带**完整 URL、path、query、token、cookie、
 * Authorization、User-Agent、自定义服务器名与字幕正文。允许保留的只有：随机匿名 requestId、
 * 通道、**匿名** scope（不可逆散列）、方法、计数、阶段耗时、HTTP 状态与错误**类别**。
 *
 * 计数单位：
 * - 一次 OkHttp Call 产生一条观测；
 * - [exchangeCount] 是该 Call 真正写到线上的 HTTP 请求数（重定向/重试会 > 1）；
 * - [connectAttempts] 是新建物理连接数（连接池复用不增加）。
 */
data class RequestObservation(
    val requestId: String,
    val channel: RequestChannel,
    /** 匿名作用域（不可逆散列），绝不等于原始 host。 */
    val scopeId: String,
    val method: String,
    val exchangeCount: Int,
    val redirectHops: Int,
    val connectAttempts: Int,
    val statusCode: Int?,
    /** 错误**类别**（异常类名），绝不是异常消息——消息可能含 URL。 */
    val errorKind: String?,
    val durationMs: Long,
    val isRange: Boolean,
) {
    init {
        require(exchangeCount >= 0) { "exchangeCount 不得为负" }
        require(redirectHops >= 0) { "redirectHops 不得为负" }
        require(connectAttempts >= 0) { "connectAttempts 不得为负" }
        require(durationMs >= 0) { "durationMs 不得为负" }
        require(requestId.isNotBlank()) { "requestId 不得为空" }
        require(method.isNotBlank()) { "method 不得为空" }
    }
}

/** 累计快照（有界聚合后的可读视图）。 */
data class NetworkExchangeSnapshot(
    val logicalCalls: Long,
    val exchanges: Long,
    val redirectHops: Long,
    val connectAttempts: Long,
    val canceledCalls: Long,
    val failedCalls: Long,
    val loopbackInbound: Long,
    val localFileReads: Long,
    val statusCounts: Map<Int, Long>,
    val perChannel: Map<RequestChannel, Long>,
    /** true 表示**存在**观测不到的路径——此时未观测数**不是** 0。 */
    val partialObservability: Boolean,
    val partialChannels: Set<RequestChannel>,
    val trackedScopes: Int,
) {
    /**
     * 覆盖率口径：只要存在不可观测通道，就不能把"未计入的交换"表述为 0。
     * 该字符串给 UI/诊断用，绝不含地址。
     */
    val coverageNote: String
        get() = if (partialObservability) {
            "PARTIAL_OBSERVABILITY: " + partialChannels.joinToString(",") { it.name } + " 未纳入计数"
        } else {
            "FULL_OBSERVABILITY"
        }
}