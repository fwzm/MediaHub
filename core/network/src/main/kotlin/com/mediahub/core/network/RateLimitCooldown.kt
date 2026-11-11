package com.mediahub.core.network

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 429 冷却的可识别失败（M15）。继承 IOException 以便既有"网络失败"路径统一处理，
 * 但**不携带任何 URL / token / 账号**；只保留脱敏作用域与剩余时长。
 */
class RateLimitedException(val scope: String, val remainingMs: Long) :
    IOException("rate limited (scope=$scope, remainingMs=$remainingMs)")

/**
 * 按作用域的有界冷却（M15-A）。
 *
 * - 使用**单调时钟**计冷却，避免设备时间变化导致负冷却；
 * - 冷却时长有**上限**（maxCooldownMs），并拒绝负值/过去时间（视为无冷却）；
 * - 作用域由调用方给出（真实 serverId 优先，未知时退化为 origin），保证 A 服 429
 *   不会被施加到 B 服；
 * - 不做任何 sleep：窗口内直接**快速失败**，绝不阻塞拦截器线程。
 */
class RateLimitCooldown(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS,
    /** 冷却表条目**总**硬上限（M15-S3 测试 5）：大量不同 origin 不得使其无限增长。 */
    private val maxEntries: Int = MAX_TRACKED_SCOPES,
    /**
     * **受保护分区**（已登记服务器 / 真实 serverId 已知）的条目上限。
     *
     * 该分区中的冷却**绝不**被未知 origin 驱逐，用于消除 M15-S4-B 的 fail-open：
     * 攻击者可控的大量未知 origin 不得把核心媒体源的冷却挤掉。
     */
    private val maxKnownScopes: Int = MAX_KNOWN_SCOPES,
) {
    /** 已知服务器（受保护分区）。 */
    private val knownDeadlines = ConcurrentHashMap<String, Long>()

    /** 未知 origin（非受保护分区；仅在**本分区内**互相淘汰）。 */
    private val unknownDeadlines = ConcurrentHashMap<String, Long>()

    /**
     * 仅保护"回收 + 淘汰 + 写入"这一小段临界区，用于保证容量上限在并发下**严格成立**。
     *
     * 注意与"用全局串行锁掩盖并发错误"的区别：本锁内**没有任何 I/O、没有任何 sleep**，
     * 不参与请求放行判定，也不序列化 [RateLimitCooldownInterceptor] 的 `chain.proceed`；
     * 它只让并发写入者对"当前条目集合"的观察与修改保持原子。
     * 读取路径（[remainingMs]）完全无锁。
     */
    private val writeLock = ReentrantLock()

    private val knownCapacity: Int = maxKnownScopes.coerceAtLeast(1).coerceAtMost(maxEntries.coerceAtLeast(1))

    /** 未知 origin 分区的容量 = 总上限 - 已知分区上限（两部分不互相挤占）。 */
    private val unknownCapacity: Int = (maxEntries.coerceAtLeast(1) - knownCapacity).coerceAtLeast(1)

    /**
     * 当前被追踪的作用域数量（容量契约的观测点；无锁读取，返回的是瞬时快照）。
     * 仅同模块测试使用，不作为对外 API。
     */
    internal fun trackedScopeCount(): Int = knownDeadlines.size + unknownDeadlines.size

    /**
     * 受保护分区当前条目数（容量契约观测点；无锁读取，瞬时快照）。
     * 仅同模块测试使用。
     */
    internal fun knownScopeCount(): Int = knownDeadlines.size

    /** 未知分区当前条目数（仅同模块测试使用）。 */
    internal fun unknownScopeCount(): Int = unknownDeadlines.size

    /** 在两个分区中查找截止时间；命中过期条目时惰性回收。 */
    private fun lookup(scope: String): Long? {
        val fromKnown = knownDeadlines[scope]
        val fromUnknown = if (fromKnown == null) unknownDeadlines[scope] else null
        val deadline = fromKnown ?: fromUnknown ?: return null
        val source = if (fromKnown != null) knownDeadlines else unknownDeadlines
        if (deadline - clockMs() <= 0L) {
            // 条件删除：仅当值仍等于读到的截止时间才删，因此并发刷新不会被误删。
            source.remove(scope, deadline)
            return null
        }
        return deadline
    }

    /** 剩余冷却毫秒；不在冷却中返回 null。 */
    fun remainingMs(scope: String): Long? {
        val deadline = lookup(scope) ?: return null
        val remaining = deadline - clockMs()
        // 上限**在每个读取点**都成立，而不只在写入点：即使时钟被回拨（或注入的时钟源
        // 非单调）使剩余时长被放大，也绝不允许报出超过协议上限的冷却。
        return remaining.coerceAtMost(maxCooldownMs)
    }

    /**
     * 记录/刷新冷却（M15-S3 测试 5 + M15-S4-B 分区保护）。
     *
     * 语义：
     * - 非正时长表示**无冷却**，清除两个分区中的已有条目；
     * - 已存在的作用域只**刷新**截止时间（now + duration），绝不累加 —— 反复续期不会变成永久冷却；
     * - [isKnownServer] = true 的条目进入**受保护分区**，只在受保护分区内按"最早到期"淘汰，
     *   **绝不被未知 origin 驱逐**；
     * - [isKnownServer] = false 的条目进入未知分区，只在未知分区内淘汰。
     *
     * 两个分区的容量之和 = [maxEntries]，因此总内存仍**有硬上限**（既不会无限扩容，
     * 也不会因为保护已知服务器而全局阻断）。
     */
    @JvmOverloads
    fun apply(scope: String, retryAfterMs: Long, isKnownServer: Boolean = false) {
        if (retryAfterMs <= 0L) {
            knownDeadlines.remove(scope)
            unknownDeadlines.remove(scope)
            return
        }
        val now = clockMs()
        val deadline = now + retryAfterMs.coerceAtMost(maxCooldownMs)
        val target = if (isKnownServer) knownDeadlines else unknownDeadlines
        val capacity = if (isKnownServer) knownCapacity else unknownCapacity
        writeLock.withLock {
            // 作用域可能因归属变化（例如先未知后已知）而换分区：先清掉旧分区条目。
            val other = if (isKnownServer) unknownDeadlines else knownDeadlines
            other.remove(scope)
            if (target.containsKey(scope)) {
                target[scope] = deadline
                return
            }
            if (target.size >= capacity) {
                // 淘汰**最早到期**的一条。该规则天然优先淘汰已过期条目
                // （过期条目截止时间必然小于任何有效条目）。
                while (target.size >= capacity) {
                    val victim = target.entries.minByOrNull { it.value } ?: break
                    target.remove(victim.key, victim.value)
                }
            }
            target[scope] = deadline
        }
    }

    fun clear() {
        knownDeadlines.clear()
        unknownDeadlines.clear()
    }

    companion object {
        const val MAX_COOLDOWN_MS: Long = 15 * 60 * 1000L

        /**
         * 冷却表容量上限：防止海量 origin（多账号 / 多云盘 / 恶意重定向制造的无限作用域）
         * 无限占用内存。256 条足以覆盖真实设备上的服务器数量级，同时把内存占用钉死为上界。
         */
        const val MAX_TRACKED_SCOPES: Int = 256

        /**
         * **受保护分区**容量（M15-S4-B）：已登记服务器的冷却绝不被未知 origin 驱逐。
         *
         * 取值理由：真实设备上配置的媒体源数量级为个位数到十几个；64 条足以容纳
         * 多账号 + 多协议（Emby/Jellyfin/WebDAV/云盘）的组合，同时给未知分区留下
         * 192 条余量，两者之和仍为 [MAX_TRACKED_SCOPES]。
         */
        const val MAX_KNOWN_SCOPES: Int = 64

        /** 未知 origin 分区容量 = [MAX_TRACKED_SCOPES] - [MAX_KNOWN_SCOPES]。 */
        const val MAX_UNKNOWN_SCOPES: Int = MAX_TRACKED_SCOPES - MAX_KNOWN_SCOPES

        /** Retry-After 缺失/非法时的保守默认值（仍受上限约束）。 */
        const val DEFAULT_COOLDOWN_MS: Long = 60 * 1000L

        /**
         * 解析 Retry-After：delta-seconds 或 HTTP-date。
         * - 数字：秒转毫秒（0 或负值表示无冷却）
         * - HTTP-date：与 nowEpochMs 比较；过去时间返回 0
         * - 缺失/格式错误：[DEFAULT_COOLDOWN_MS]
         */
        fun parseRetryAfter(header: String?, nowEpochMs: Long): Long {
            val raw = header?.trim().orEmpty()
            if (raw.isEmpty()) return DEFAULT_COOLDOWN_MS
            raw.toLongOrNull()?.let { seconds ->
                // 饱和乘法：seconds * 1000 会溢出成负数，而负值在 apply() 里表示"无冷却"，
                // 于是**超大 Retry-After 反而会取消冷却**（fail-open）。先按上限截断再乘。
                if (seconds <= 0L) return 0L
                return if (seconds >= MAX_COOLDOWN_MS / 1000L) MAX_COOLDOWN_MS else seconds * 1000L
            }
            return runCatching {
                val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                val delta = target - nowEpochMs
                when {
                    delta <= 0L -> 0L
                    delta >= MAX_COOLDOWN_MS -> MAX_COOLDOWN_MS
                    else -> delta
                }
            }.getOrDefault(DEFAULT_COOLDOWN_MS)
        }
    }
}

/**
 * 429 冷却拦截器：窗口内快速失败；收到 429 时按作用域记录冷却。
 *
 * M15-S3 测试 4：注册为 OkHttp **network interceptor**（逐跳生效），与 [UserAgentInterceptor]、
 * [OriginScopedCredentialInterceptor] 同一模式。原因：应用拦截器只能看到"原始请求 + 最终响应"，
 * 其 request.url 恒为入口 URL；重定向链中真正返回 429 的是另一跳，按入口 URL 记账会造成
 * **跨服务器误冷却**。network interceptor 的 `chain.request()` 是当前跳的真实 URL，
 * 因此归属、冷却键与快速失败都落在真正相关的那一跳上。
 *
 * 由于它位于 [HttpClientFactory] 的 RetryOnceInterceptor 之内，后者必须显式排除
 * [RateLimitedException]（不再重试），否则冷却失败会被当作可重试 IOException 放大请求。
 */
class RateLimitCooldownInterceptor(
    private val cooldown: RateLimitCooldown,
    private val scopeOf: (HttpUrl) -> String?,
    /**
     * true = **逐跳记账 + 快速失败**（注册为 network interceptor）；
     * false = **仅入口作用域快速失败，不记账**（注册为 application interceptor）。
     *
     * 必须拆成两个实例的原因（M15-S3 测试 4/6 评审裁定）：
     * - OkHttp 的 ConnectInterceptor 位于 network interceptor **之上**，连接在 network
     *   拦截器执行前就已建立/复用。若只在 network 层做冷却判定，被冷却的作用域**每次**
     *   仍会发起 TCP/TLS 连接（抛异常还会触发 exitNetworkInterceptorExchange →
     *   detachWithViolence 关闭该连接），与"窗口内零出网"的契约不符；
     * - 应用层拦截器位于 ConnectInterceptor 之上，因此能真正做到**连接前**失败；
     * - 但应用层只能看到"原始请求 + 最终响应"，用它在重定向链上记账会把第三方跳的 429
     *   误记到入口服务器（跨服务器误冷却）。
     * 因此：应用层实例只做入口快速失败（record429=false），network 层实例负责逐跳归属记账。
     */
    private val record429: Boolean = true,
) : Interceptor {

    /** 精确 origin 键：scheme://host:port（不含路径，避免把同源不同服务器混为一谈）。 */
    private fun originKey(url: HttpUrl): String = url.scheme + "://" + url.host + ":" + url.port

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 未知 serverId 时的回退键必须是**精确 origin**（scheme+host+port）。
        // 用裸 host 会把同主机不同端口的另一台服务器误判为同一作用域，
        // 导致 A 服 429 冷却静默阻断 B 服（M15-S3 测试 1 已复现）。
        // 区分"已知服务器"与"未知 origin"（M15-S4-B）：
        // 解析出真实 serverId 的条目进入受保护分区，绝不被未知 origin 洪水驱逐；
        // 回退到精确 origin 的条目只在未知分区内互相淘汰。
        val resolved = scopeOf(request.url)
        val scope = resolved ?: originKey(request.url)
        val isKnownServer = resolved != null
        cooldown.remainingMs(scope)?.let { remaining ->
            throw RateLimitedException(scope = scope, remainingMs = remaining)
        }
        val response = chain.proceed(request)
        if (record429 && response.code == 429) {
            val retryAfterMs = RateLimitCooldown.parseRetryAfter(
                header = response.header("Retry-After"),
                nowEpochMs = System.currentTimeMillis(),
            )
            cooldown.apply(scope, retryAfterMs, isKnownServer = isKnownServer)
        }
        return response
    }
}
