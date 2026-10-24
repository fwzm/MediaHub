package com.mediahub.core.common.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * 回执帧的四个稳定码（与 Windows SourceSyncReceiptReasons 逐字同名同序）。
 *
 * 这是独立封闭集，与传输十码、认证五码、TLS 四码互不相交：
 * 「对端回了别的字节」既不是帧格式失败，也不是认证失败，更不是 TLS 失败，
 * 折进任何一张表都会让验收日志说错话。
 */
object SourceSyncReceiptReasons {

    /** 连接在对端回一个字节之前就结束了：对端没有确认收到。 */
    const val RECEIPT_MISSING: String = "receipt-missing"

    /** 对端把连接开着撑过了预算，却没有回一个字节。 */
    const val RECEIPT_TIMEOUT: String = "receipt-timeout"

    /** 对端回了一个字节，但不是回执类型，所以它没有确认这封信封。 */
    const val RECEIPT_INVALID: String = "receipt-invalid"

    /** 本端根本没有回执通道：流不可写，或写完读不回来。 */
    const val RECEIPT_NOT_ENABLED: String = "receipt-not-enabled"

    /** 全部码，顺序与 Windows 的 All 列表一致（顺序本身被测试钉住）。 */
    val ALL: List<String> = listOf(
        RECEIPT_MISSING,
        RECEIPT_TIMEOUT,
        RECEIPT_INVALID,
        RECEIPT_NOT_ENABLED,
    )
}

/**
 * 写或等一次回执的结果（与 Windows SourceSyncReceiptResult 同义）。
 *
 * 三种状态必须分得清：confirmed=true 是「字节到了/发出去了」；
 * confirmed=false 且 reason 非空是四码之一；confirmed=false 且 reason 为 null
 * 是「本端刻意沉默」（回执关着，或解码闸门拒了这份载荷）——这不是失败，是沉默。
 */
class SourceSyncReceiptResult private constructor(
    val confirmed: Boolean,
    val reason: String?,
    val detail: String?,
    val bytesRead: Int,
    val elapsedMs: Long,
) {

    /** 是否沉默（不是失败）：回执关着，或闸门拒收，所以本端一个字节都没写。 */
    val isSilent: Boolean get() = !confirmed && reason == null

    override fun toString(): String =
        "SourceSyncReceiptResult { Confirmed = " + confirmed +
            ", Reason = " + (reason ?: "<none>") +
            ", BytesRead = " + bytesRead +
            ", ElapsedMs = " + elapsedMs + " }"

    companion object {

        /** 回执字节写出去（或收到了），耗时 elapsedMs。 */
        fun confirmed(elapsedMs: Long): SourceSyncReceiptResult =
            SourceSyncReceiptResult(true, null, null, SourceSyncReceipt.RECEIPT_FRAME_BYTES, elapsedMs)

        /** 没成：reason 是四码之一，detail 是给人看的诊断。 */
        fun failed(reason: String, detail: String?, elapsedMs: Long = 0L): SourceSyncReceiptResult =
            SourceSyncReceiptResult(false, reason, detail, 0, elapsedMs)

        /** 刻意沉默：回执关着，或解码闸门拒了载荷，所以一个字节都没写。 */
        fun silent(detail: String?): SourceSyncReceiptResult =
            SourceSyncReceiptResult(false, null, detail, 0, 0L)
    }
}

/**
 * 本端要不要参与回执（对应 Windows SourceSyncReceiptOptions）。
 *
 * 默认 [Off]：不多写一个字节、不多读一次、不多等一毫秒，线上字节与 W6-22 至今完全一致。
 * 关闭是默认值不是保守，而是契约：冻结向量时刻在跑，一开就全废。
 */
sealed class SourceSyncReceiptOptions {

    /** 是否参与回执。 */
    abstract val enabled: Boolean

    /** 等回执的预算，毫秒（只有 [Awaiting] 真的用它）。 */
    abstract val budgetMs: Int

    /** 默认：不写回执，也不等回执。 */
    object Off : SourceSyncReceiptOptions() {
        override val enabled: Boolean get() = false
        override val budgetMs: Int get() = 0
        override fun toString(): String = "SourceSyncReceiptOptions { Enabled = false }"
    }

    /** 接收端角色：信封解码成功后写一个裸 0x04。没有等待，所以没有预算。 */
    class Writing internal constructor() : SourceSyncReceiptOptions() {
        override val enabled: Boolean get() = true
        override val budgetMs: Int get() = SourceSyncReceiptOptions.DEFAULT_BUDGET_MS
        override fun toString(): String = "SourceSyncReceiptOptions { Enabled = true, Role = write }"
    }

    /** 发送端角色：最后一块（和 tag 帧）之后读一个字节，预算内没到就算没确认。 */
    class Awaiting internal constructor(override val budgetMs: Int) : SourceSyncReceiptOptions() {
        override val enabled: Boolean get() = true
        override fun toString(): String =
            "SourceSyncReceiptOptions { Enabled = true, Role = await, BudgetMs = " + budgetMs + " }"
    }

    companion object {

        /** 默认等待预算：2000 毫秒（与 Windows DefaultBudgetMilliseconds 一致）。 */
        const val DEFAULT_BUDGET_MS: Int = 2_000

        /** 接收端：写回执。 */
        fun writing(): Writing = Writing()

        /** 发送端：等回执；budgetMs 为 null 或非正时取 [DEFAULT_BUDGET_MS]。 */
        fun awaiting(budgetMs: Int? = null): Awaiting = Awaiting(resolveBudget(budgetMs))

        /** 布尔开关到接收端选项：false 一律 Off，避免调用方自己拼 null。 */
        fun writingOrOff(enabled: Boolean): SourceSyncReceiptOptions = if (enabled) writing() else Off

        /** 布尔开关到发送端选项。 */
        fun awaitingOrOff(enabled: Boolean, budgetMs: Int? = null): SourceSyncReceiptOptions =
            if (enabled) awaiting(budgetMs) else Off

        private fun resolveBudget(budgetMs: Int?): Int =
            if (budgetMs == null || budgetMs <= 0) DEFAULT_BUDGET_MS else budgetMs
    }
}

/**
 * 回执帧（kind 0x04）唯一读写点（对应 Windows SourceSyncReceipt）。
 *
 * 形态：一个裸的 0x04 字节，没有长度前缀。W6-37 把它钉成一字节，
 * 这样对端可以「等一个 0x04」而不必再自带一套帧长度判定；
 * 对端回别的字节就报 receipt-invalid，而不是报成帧长度事故。
 * 它走在最后一块之后（认证开着时在 tag 帧之后），且只有接收端会写。
 *
 * 全仓只有这里能往传输流上写 0x04。
 */
object SourceSyncReceipt {

    /** 回执帧的类型字节。 */
    const val RECEIPT_FRAME_KIND: Int = 0x04

    /** 回执在线上占几字节：一个，只有类型字节。 */
    const val RECEIPT_FRAME_BYTES: Int = 1

    /**
     * 写一个回执字节并 flush（对应 Windows WriteAsync）。
     *
     * 写不出去不抛异常：返回 receipt-not-enabled，调用方据此如实说「对端等不到回执」。
     */
    fun write(output: OutputStream?): SourceSyncReceiptResult {
        if (output == null) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
                "本端写不出回执：没有可写的流，所以对端看不到确认。",
            )
        }
        val startedAt = System.nanoTime()
        return try {
            output.write(RECEIPT_FRAME_KIND)
            output.flush()
            SourceSyncReceiptResult.confirmed(elapsedSince(startedAt))
        } catch (error: IOException) {
            SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
                "回执字节写不出去，对端因此看不到确认：" + error.javaClass.simpleName,
                elapsedSince(startedAt),
            )
        } catch (error: Exception) {
            SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
                "回执字节写不出去，对端因此看不到确认：" + error.javaClass.simpleName,
                elapsedSince(startedAt),
            )
        }
    }

    /**
     * 等对端的一个回执字节（对应 Windows AwaitAsync）。
     *
     * 只读一个字节：读到 0x04 才算确认；读到别的字节是 receipt-invalid
     * （回别的字节的对端不是在回答这套协议）；读到流结束是 receipt-missing；
     * 超时是 receipt-timeout。超时由调用方把底层读超时设成预算来实现（本函数不自己起线程）。
     */
    fun await(
        input: InputStream?,
        budgetMs: Int = SourceSyncReceiptOptions.DEFAULT_BUDGET_MS,
    ): SourceSyncReceiptResult {
        if (input == null) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
                "本端读不到回执：没有可读的流，所以这个「成功」没有送达证据。",
            )
        }
        val startedAt = System.nanoTime()
        val value = try {
            input.read()
        } catch (timeout: SocketTimeoutException) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_TIMEOUT,
                "对端把连接开着过了 " + budgetMs + " 毫秒却没回一个字节，所以它没有确认这封信封。",
                elapsedSince(startedAt),
            )
        } catch (error: IOException) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_MISSING,
                "回执字节到之前连接就断了，所以对端没有确认这封信封：" + error.javaClass.simpleName,
                elapsedSince(startedAt),
            )
        }
        if (value < 0) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_MISSING,
                "对端没有回回执字节就关了连接，所以这次发送不是「信封被接受」的证据：" +
                    "拒收载荷的接收端也是关连接、不回答。",
                elapsedSince(startedAt),
            )
        }
        val kind = value and 0xFF
        if (kind != RECEIPT_FRAME_KIND) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_INVALID,
                "对端回的是 0x" + Integer.toHexString(kind) + "，不是回执类型 0x04，所以它没有确认这封信封。",
                elapsedSince(startedAt),
            )
        }
        return SourceSyncReceiptResult.confirmed(elapsedSince(startedAt))
    }

    /**
     * 接收端的完整判定：先看开关，再看解码闸门，最后才写。
     *
     * 闸门 gate 是「这封信封我解码得出来吗」；返回 false 时刻意沉默（confirmed=false、reason=null）。
     * 这一步与 Windows 端点层的 receiptGate 同位置：写回执之前判，所以解不出来的载荷永远不会被确认。
     */
    fun acknowledge(
        output: OutputStream?,
        envelope: ByteArray,
        options: SourceSyncReceiptOptions?,
        gate: ((ByteArray) -> Boolean)? = null,
    ): SourceSyncReceiptResult {
        if (options == null || !options.enabled) {
            return SourceSyncReceiptResult.silent("回执关着，本端一个字节都没写。")
        }
        if (output == null) {
            return SourceSyncReceiptResult.failed(
                SourceSyncReceiptReasons.RECEIPT_NOT_ENABLED,
                "本端写不出回执：没有可写的流，所以对端看不到确认。",
            )
        }
        if (gate != null && !gate(envelope)) {
            return SourceSyncReceiptResult.silent("解码闸门拒了这份载荷，本端刻意沉默，对端会看到没有回执。")
        }
        return write(output)
    }

    private fun elapsedSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
