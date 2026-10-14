package com.mediahub.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M15-S3 测试 5：冷却表容量与过期。
 *
 * 契约：
 * - 条目数有**明确硬上限**，海量不同 origin 不得使其无限增长；
 * - 过期条目按设计回收，容量压力下**不得淘汰仍然有效**的冷却；
 * - 过期边界时刻行为确定（到点即释放）；
 * - 重复 apply 只**刷新**截止时间，不累积成永久冷却；
 * - 并发写入不得突破容量约束；
 * - 时钟语义为单调时钟，系统时间跳变不产生负时长/异常长期冷却。
 *
 * 全部使用可注入时钟，不使用 sleep、不改系统时间。
 */
class RateLimitCapacityTest {

    private fun cooldown(now: () -> Long, maxEntries: Int = RateLimitCooldown.MAX_TRACKED_SCOPES) =
        RateLimitCooldown(clockMs = now, maxEntries = maxEntries)

    // ---------- 1. 容量硬上限 ----------

    @Test
    fun `a flood of distinct origins never exceeds the tracked scope capacity`() {
        val cold = cooldown({ 0L })
        // 远超容量的不同 origin（模拟多账号 / 多云盘 / 恶意重定向制造的海量作用域）
        for (i in 1..RateLimitCooldown.MAX_TRACKED_SCOPES * 8) {
            cold.apply("https://host-$i.example.com:443", 60_000L)
        }
        // 必须精确等于上限：仅断言 "<= 上限" 无法区分"正常淘汰"与
        // "满员后静默丢弃新作用域"或"整表清空"这两种 fail-open。
        assertEquals(
            "冷却表必须恰好装满上限（静默丢弃新作用域会小于上限）",
            RateLimitCooldown.MAX_TRACKED_SCOPES,
            cold.trackedScopeCount(),
        )
        // 最近写入的作用域必须被保留：淘汰策略是"最早到期"，而不是清空整表
        assertNotNull(
            "最新作用域不得被丢弃",
            cold.remainingMs("https://host-${RateLimitCooldown.MAX_TRACKED_SCOPES * 8}.example.com:443"),
        )
    }

    @Test
    fun `capacity pressure evicts the soonest deadline and keeps the freshest cooldowns`() {
        var now = 0L
        val limit = 3
        val cold = cooldown({ now }, maxEntries = limit)

        // 依次写入不同时长的冷却：short 最早到期
        cold.apply("scope-short", 1_000L)
        cold.apply("scope-mid", 5_000L)
        cold.apply("scope-long", 60_000L)
        assertEquals(limit, cold.trackedScopeCount())

        // 第 4 个作用域触发容量淘汰
        cold.apply("scope-new", 30_000L)
        assertEquals("容量不得被突破", limit, cold.trackedScopeCount())

        assertNull("最早到期的冷却应被优先淘汰", cold.remainingMs("scope-short"))
        assertNotNull("尚未到期且更晚到期的冷却必须保留", cold.remainingMs("scope-long"))
        assertNotNull("新写入的作用域必须在表中", cold.remainingMs("scope-new"))
    }

    // ---------- 2. 过期回收 ----------

    @Test
    fun `expired entries are reclaimed instead of evicting still valid cooldowns`() {
        var now = 0L
        val limit = 2
        val cold = cooldown({ now }, maxEntries = limit)

        cold.apply("scope-expiring", 1_000L)
        cold.apply("scope-valid", 60_000L)
        assertEquals(limit, cold.trackedScopeCount())

        // 让第一个作用域过期，再写入新作用域：应回收过期条目，而不是淘汰仍然有效的
        now = 1_000L
        cold.apply("scope-new", 30_000L)

        assertEquals("容量不得被突破", limit, cold.trackedScopeCount())
        assertNotNull(
            "仍然有效的冷却不得因过期条目占位而被误淘汰",
            cold.remainingMs("scope-valid"),
        )
        assertNotNull(cold.remainingMs("scope-new"))
        assertNull("过期条目必须已被回收", cold.remainingMs("scope-expiring"))
    }

    @Test
    fun `an expired scope is released exactly at its deadline`() {
        var now = 0L
        val cold = cooldown({ now })
        cold.apply("scope", 2_000L)

        now = 1_999L
        assertNotNull("截止点之前仍须冷却", cold.remainingMs("scope"))
        now = 2_000L
        assertNull("到达截止点即释放（边界确定）", cold.remainingMs("scope"))
        assertEquals("释放后条目必须被移除", 0, cold.trackedScopeCount())
    }

    // ---------- 3. 刷新语义：不得造成永久冷却 ----------

    @Test
    fun `repeated apply refreshes the deadline instead of extending it permanently`() {
        var now = 0L
        val cold = cooldown({ now })
        val window = 10_000L

        // 在窗口内反复续期：截止时间必须始终是 now+window，绝不累积
        repeat(20) {
            cold.apply("scope", window)
            val remaining = cold.remainingMs("scope")
            assertNotNull(remaining)
            assertEquals("每次 apply 必须只刷新为 now+window，实际=$remaining", window, remaining)
            now += 1_000L
        }

        // 时钟推进到最后一轮之后的 window：必须释放，不得变成永久冷却
        now += window
        assertNull("反复续期不得造成永久冷却", cold.remainingMs("scope"))
    }

    @Test
    fun `a non positive retry after clears any existing cooldown`() {
        val cold = cooldown({ 0L })
        cold.apply("scope", 60_000L)
        assertNotNull(cold.remainingMs("scope"))

        cold.apply("scope", 0L)
        assertNull("Retry-After 为 0 必须清除冷却", cold.remainingMs("scope"))
        assertEquals(0, cold.trackedScopeCount())

        cold.apply("scope", -1_000L)
        assertNull("负时长必须视为无冷却", cold.remainingMs("scope"))
    }

    // ---------- 4. 并发不得突破容量 ----------

    @Test
    fun `concurrent writers cannot exceed the capacity bound`() {
        val limit = 32
        val cold = RateLimitCooldown(clockMs = { 0L }, maxEntries = limit)
        val threads = 8
        val perThread = 400
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            Thread {
                start.await()
                repeat(perThread) { i ->
                    cold.apply("scope-$t-$i", 60_000L)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("并发写入必须全部完成", done.await(30, TimeUnit.SECONDS))

        assertTrue(
            "并发写入后条目数仍必须 <= 上限：上限=$limit 实际=${cold.trackedScopeCount()}",
            cold.trackedScopeCount() <= limit,
        )
        assertTrue("清单不得为空", cold.trackedScopeCount() > 0)
    }

    @Test
    fun `concurrent readers and writers keep the capacity and do not corrupt entries`() {
        val limit = 16
        val cold = RateLimitCooldown(clockMs = { 0L }, maxEntries = limit)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)

        repeat(4) { t ->
            Thread {
                start.await()
                repeat(500) { i -> cold.apply("write-$t-$i", 60_000L) }
                done.countDown()
            }.start()
        }
        repeat(4) {
            Thread {
                start.await()
                repeat(2_000) { i -> cold.remainingMs("read-$i") }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("并发读写必须全部完成", done.await(30, TimeUnit.SECONDS))

        assertTrue(
            "并发读写后不得突破容量：上限=$limit 实际=${cold.trackedScopeCount()}",
            cold.trackedScopeCount() <= limit,
        )
    }

    // ---------- 5. 时钟语义 ----------

    @Test
    fun `a backwards clock jump can never yield a negative or over long cooldown`() {
        // 时钟被往**回**拨（模拟系统时间跳变）。冷却实现只允许把时钟当单调源读取，
        // 因此要么仍在窗口内、要么已释放，但绝不允许出现负剩余或超上限的剩余。
        var now = 1_000_000L
        val cap = RateLimitCooldown.MAX_COOLDOWN_MS
        val cold = cooldown({ now })
        cold.apply("scope", 30_000L)

        for (jump in listOf(-1L, -30_000L, -365L * 24 * 3600 * 1000)) {
            now += jump
            val remaining = cold.remainingMs("scope")
            if (remaining != null) {
                assertTrue("剩余时长不得为负或为 0（实际=$remaining）", remaining > 0L)
                assertTrue(
                    "上限必须在每个读取点成立：时钟被回拨也不得放大冷却（实际=$remaining）",
                    remaining <= cap,
                )
            }
        }
        // 墙钟回拨不得让冷却变成"永久"：单调时钟正常前进时仍须按期释放
        now = 1_000_000L + 30_000L
        assertNull("时钟恢复正常后必须按原截止点释放", cold.remainingMs("scope"))

        // 正向越过截止点必须释放
        now += 30_000L
        assertNull("越过截止点即释放", cold.remainingMs("scope"))
    }

    @Test
    fun `cooldown duration is derived from retry after and never exceeds the cap`() {
        val cold = cooldown({ 0L })
        // 协议上限：Retry-After 解析结果本身就不得突破 15 分钟
        val parsed = RateLimitCooldown.parseRetryAfter("86400", nowEpochMs = 0L)
        assertTrue(
            "Retry-After 解析必须封顶到 ${RateLimitCooldown.MAX_COOLDOWN_MS}ms，实际=$parsed",
            parsed <= RateLimitCooldown.MAX_COOLDOWN_MS,
        )
        cold.apply("scope", parsed)
        val remaining = cold.remainingMs("scope")
        assertNotNull(remaining)
        assertTrue(
            "冷却时长不得超过上限，实际=$remaining",
            remaining!! <= RateLimitCooldown.MAX_COOLDOWN_MS,
        )
    }

    @Test
    fun `per hop scopes from the same host are tracked as distinct entries`() {
        val cold = cooldown({ 0L })
        // scheme+host+port 精确 origin：同 host 不同端口必须各占一条
        listOf(
            "https://media.example.com:8920",
            "http://media.example.com:8920",
            "https://media.example.com:443",
        ).forEach { cold.apply(it, 60_000L) }

        assertEquals("三个不同 origin 必须各占一条", 3, cold.trackedScopeCount())
        assertNotNull(cold.remainingMs("https://media.example.com:8920"))
        assertNotNull(cold.remainingMs("http://media.example.com:8920"))
        assertNotNull(cold.remainingMs("https://media.example.com:443"))
    }
}
