package com.mediahub.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response

/**
 * OkHttp [EventListener]，把**物理**网络事件翻译成 [RequestObservation]（M16-S0）。
 *
 * 为什么不能只用 interceptor：应用/网络拦截器只看到"请求-响应"，看不到重定向产生的
 * **额外 exchange**、连接池复用与物理连接建立。EventListener 才能区分：
 * - [requestHeadersStart]：真正写到线上的一个 HTTP 请求 → 一次 **exchange**
 * - [connectStart]：新建物理连接（连接池复用不触发）
 * - [canceled] / [callFailed]：终止状态
 *
 * 隐私：本类**只**读取不可识别信息（host+port 经散列、方法、状态码、异常**类名**）；
 * 绝不读取或记录 URL path、query、任何 header（token/cookie/Authorization/UA 都不碰）。
 * 因此它不会因为"忘记脱敏"而泄漏凭据——读取面本身就不含凭据。
 */
class NetworkExchangeRecorder(
    private val ledger: NetworkExchangeLedger,
    private val channel: RequestChannel,
    /** 提供匿名 requestId（默认随机），便于把一次 Call 的多条事件串起来。 */
    private val requestIdFactory: () -> String = { java.util.UUID.randomUUID().toString().take(12) },
) : EventListener() {

    /** 每个 Call 一份可变状态；OkHttp 保证同一 Call 的事件在同一线程序列上，但仍用并发结构兜底。 */
    private class CallState(val requestId: String, val scopeId: String, val method: String) {
        val exchanges = AtomicInteger()
        val connectAttempts = AtomicInteger()
        val redirectHops = AtomicInteger()
        @Volatile var statusCode: Int? = null
        @Volatile var errorKind: String? = null
        @Volatile var isRange: Boolean = false
        @Volatile var canceled: Boolean = false
        @Volatile var finished: Boolean = false
    }

    private val states = java.util.concurrent.ConcurrentHashMap<Call, CallState>()

    /**
     * 已结算（已记账）的 Call。
     *
     * 实测的 OkHttp 4.12 事件次序（见 NetworkExchangeObservationTest 的次序断言）：
     * - execute 前 cancel  ：canceled → callStart → callFailed（**没有** callEnd）
     * - 正常完成           ：callStart → … → callEnd（完成后 cancel 仍会再发一次 canceled）
     * - 在途取消           ：callStart → … → canceled → callFailed
     * 因此 canceled() 既可能在 callStart **之前**触发，也可能在 callEnd **之后**再次触发；
     * 必须用本集合保证一条逻辑请求**恰好记账一次**。
     */
    private val settled = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()

    override fun callStart(call: Call) {
        // 该 Call 已被"执行前取消"路径结算过：不能再建状态，否则结束事件会二次记账。
        if (settled.contains(call)) return
        // 只取 host/port 做匿名 scope；**不读 path / query / headers**
        val host = call.request().url.host
        val port = call.request().url.port
        val state = CallState(
            requestId = requestIdFactory(),
            scopeId = ledger.anonymousScope(host, port),
            method = call.request().method,
        )
        // Range 是媒体语义，需要保留（不含路径）；只判断 header 是否存在，不读取其值之外的信息
        state.isRange = call.request().header("Range") != null
        states[call] = state
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        states[call]?.connectAttempts?.incrementAndGet()
    }

    /** 每次真正写到线上的请求（含重定向后的后续跳）都会触发。 */
    override fun requestHeadersStart(call: Call) {
        states[call]?.exchanges?.incrementAndGet()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val state = states[call] ?: return
        state.statusCode = response.code
        // 逐跳记录状态：重定向链中每一跳的响应状态都必须保留（3xx 不能被最终 200 覆盖）。
        ledger.recordStatus(response.code)
        // 重定向跳数必须按**状态码**累计，不能读 response.priorResponse：
        // responseHeadersEnd 在 RetryAndFollowUpInterceptor 挂接 priorResponse **之前**触发，
        // 因此该字段在此恒为 null（实测：2 跳链里 exchanges=2 但 priorResponse 链为 0）。
        // 3xx + Location 就是一次真实的额外 exchange，逐跳累加即可。
        if (response.code in REDIRECT_CODES && response.header("Location") != null) {
            state.redirectHops.incrementAndGet()
        }
    }

    override fun canceled(call: Call) {
        val state = states[call]
        if (state == null) {
            // 取消发生在 callStart 之前（execute 前 cancel）：OkHttp 不会再触发 callStart/callEnd，
            // 但这条逻辑请求**确实存在且零交换**，必须记账，否则"取消 = 不发生"会被漏统计。
            // 恰好记一次：执行前取消走这条路径；已完成后再收到 canceled 则直接忽略。
            if (!settled.add(call)) return
            val host = call.request().url.host
            val port = call.request().url.port
            ledger.recordCanceled()
            ledger.record(
                RequestObservation(
                    requestId = requestIdFactory(),
                    channel = channel,
                    scopeId = ledger.anonymousScope(host, port),
                    method = call.request().method,
                    exchangeCount = 0,
                    redirectHops = 0,
                    connectAttempts = 0,
                    statusCode = null,
                    errorKind = "Canceled",
                    durationMs = 0L,
                    isRange = call.request().header("Range") != null,
                ),
            )
            return
        }
        state.canceled = true
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val state = states[call] ?: return
        // 只保留异常**类名**：异常消息可能含 URL。
        state.errorKind = ioe.javaClass.simpleName
        if (state.canceled || ioe is java.io.InterruptedIOException) state.canceled = true
        // OkHttp 在 callFailed 之后**不会**再触发 callEnd，必须在此结算，否则该逻辑请求会丢。
        finish(call)
    }

    override fun callEnd(call: Call) {
        finish(call)
    }

    private fun finish(call: Call) {
        val state = states.remove(call) ?: return
        // 唯一结算点：无论经由 callEnd 还是 callFailed，最多记账一次。
        if (!settled.add(call)) return
        if (state.finished) return
        state.finished = true
        if (state.canceled) ledger.recordCanceled()
        ledger.record(
            RequestObservation(
                requestId = state.requestId,
                channel = channel,
                scopeId = state.scopeId,
                method = state.method,
                exchangeCount = state.exchanges.get(),
                redirectHops = state.redirectHops.get(),
                connectAttempts = state.connectAttempts.get(),
                statusCode = state.statusCode,
                errorKind = state.errorKind,
                durationMs = 0L,
                isRange = state.isRange,
            ),
        )
    }

    companion object {
        /** 会触发额外 exchange 的重定向状态码。 */
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** 便于以工厂形式挂到 OkHttp。 */
        fun factory(
            ledger: NetworkExchangeLedger,
            channel: RequestChannel,
        ): EventListener.Factory = EventListener.Factory { NetworkExchangeRecorder(ledger, channel) }
    }
}