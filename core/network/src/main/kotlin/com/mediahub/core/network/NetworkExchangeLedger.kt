package com.mediahub.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 有界的网络交换聚合台账（M16-S0）。
 *
 * 设计约束：
 * - **累计计数永不丢失**：即使按 scope 的明细被有界裁剪，总量仍然准确；
 * - **内存有界**：按 scope 的明细与观测环形缓冲都有硬上限，避免长播放会话无限增长；
 * - **匿名化**：scope 由 host+port 经带盐不可逆散列得到，台账不保存原始 host；
 * - **不伪造 0**：不可观测的通道必须经 [recordUnobservable] 显式登记，
 *   并在快照里以 [NetworkExchangeSnapshot.partialObservability] 呈现。
 *
 * 线程安全：所有写入走 ConcurrentHashMap / AtomicLong，可在 OkHttp 回调线程并发调用。
 */
class NetworkExchangeLedger(
    /** 按 scope 保留的明细上限（超出后新 scope 不再新增明细，但累计计数继续）。 */
    private val maxTrackedScopes: Int = DEFAULT_MAX_TRACKED_SCOPES,
    /** 观测环形缓冲上限。 */
    private val maxRetainedObservations: Int = DEFAULT_MAX_OBSERVATIONS,
) {
    private val salt: String = java.util.UUID.randomUUID().toString()

    private val logicalCalls = AtomicLong()
    private val exchanges = AtomicLong()
    private val redirectHops = AtomicLong()
    private val connectAttempts = AtomicLong()
    private val canceledCalls = AtomicLong()
    private val failedCalls = AtomicLong()
    private val loopbackInbound = AtomicLong()
    private val localFileReads = AtomicLong()

    private val statusCounts = ConcurrentHashMap<Int, AtomicLong>()
    private val scopeExchangeCounts = ConcurrentHashMap<String, AtomicLong>()
    private val perChannel = ConcurrentHashMap<RequestChannel, AtomicLong>()
    private val partialChannels = ConcurrentHashMap.newKeySet<RequestChannel>()
    private val recent = ArrayDeque<RequestObservation>()
    private val recentLock = Any()

    /** 把 host+port 变成**不可逆**、**不含原文**的匿名 scope。 */
    fun anonymousScope(host: String, port: Int): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + "|" + host + "|" + port).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "s" + hex.take(12)
    }

    /** 记录一条完整观测。 */
    fun record(observation: RequestObservation) {
        logicalCalls.incrementAndGet()
        exchanges.addAndGet(observation.exchangeCount.toLong())
        redirectHops.addAndGet(observation.redirectHops.toLong())
        connectAttempts.addAndGet(observation.connectAttempts.toLong())
        // 注意：状态码**不**在此累计。状态按"每个 exchange 的响应"由
        // [recordStatus] 逐跳记录，否则重定向链中间跳的 3xx 会被最终状态覆盖而丢失。
        if (observation.errorKind != null) failedCalls.incrementAndGet()
        perChannel.computeIfAbsent(observation.channel) { AtomicLong() }.incrementAndGet()

        // 按 scope 的明细有界：达到上限后不再新增 key，但累计计数已在上方完成
        val existing = scopeExchangeCounts[observation.scopeId]
        if (existing != null) {
            existing.addAndGet(observation.exchangeCount.toLong())
        } else if (scopeExchangeCounts.size < maxTrackedScopes) {
            scopeExchangeCounts[observation.scopeId] = AtomicLong(observation.exchangeCount.toLong())
        }

        synchronized(recentLock) {
            recent.addLast(observation)
            while (recent.size > maxRetainedObservations) recent.removeFirst()
        }
    }

    /**
     * 记录**单个 exchange** 的响应状态（逐跳）。
     *
     * 语义：一次逻辑 Call 若经重定向产生 N 次交换，就会有 N 次状态记录。
     * 因此 statusCounts 反映的是"线上交换的状态分布"，而不是"逻辑请求的最终状态"。
     */
    fun recordStatus(code: Int) {
        statusCounts.computeIfAbsent(code) { AtomicLong() }.incrementAndGet()
    }

    /** 一次逻辑请求的中途状态（由 recorder 在最终事件里组装）。 */
    fun recordCanceled() {
        canceledCalls.incrementAndGet()
    }

    /** mpv 连到本桥 127.0.0.1 的**下游接入**：单列，不计入上游出网。 */
    fun recordLoopbackInbound() {
        loopbackInbound.incrementAndGet()
    }

    /** 本地文件读取：根本不是网络请求。 */
    fun recordLocalFileRead() {
        localFileReads.incrementAndGet()
    }

    /**
     * 登记一条**观测不到**的通道。
     *
     * 语义上是"未知"而不是"0"：快照会因此把 [NetworkExchangeSnapshot.partialObservability]
     * 置为 true，避免把未知当成零来汇报（V8 §三明确禁止）。
     */
    fun recordUnobservable(channel: RequestChannel) {
        partialChannels.add(channel)
    }

    /** 最近保留的观测（有界）。 */
    fun observations(): List<RequestObservation> = synchronized(recentLock) { recent.toList() }

    /** 当前保留的作用域明细条数。 */
    val trackedScopes: Int get() = scopeExchangeCounts.size

    fun snapshot(): NetworkExchangeSnapshot = NetworkExchangeSnapshot(
        logicalCalls = logicalCalls.get(),
        exchanges = exchanges.get(),
        redirectHops = redirectHops.get(),
        connectAttempts = connectAttempts.get(),
        canceledCalls = canceledCalls.get(),
        failedCalls = failedCalls.get(),
        loopbackInbound = loopbackInbound.get(),
        localFileReads = localFileReads.get(),
        statusCounts = statusCounts.mapValues { it.value.get() },
        perChannel = perChannel.mapValues { it.value.get() },
        partialObservability = partialChannels.isNotEmpty(),
        partialChannels = partialChannels.toSet(),
        trackedScopes = scopeExchangeCounts.size,
    )

    companion object {
        const val DEFAULT_MAX_TRACKED_SCOPES = 64
        const val DEFAULT_MAX_OBSERVATIONS = 128
    }
}