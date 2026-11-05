package com.mediahub.core.common.sync

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * 投递确认的唯一稳定码（与 Windows SourceSyncDeliveryReasons 逐字同名同义）。
 *
 * 这是独立封闭集，与传输十码、认证五码、TLS 四码、回执四码都互不相交：
 * 「帧写完了但对端没在预算内关连接」既不是传输失败，也不是认证失败、TLS 失败或回执失败。
 * 折进任何一张表都会让验收日志说错话——字节确实离开了本机，唯一没答案的是「对端收下了吗」。
 *
 * 存在的理由是 W6-28 的 F 例：明文发送端报成功时，对端一个信封字节都没读。
 * 需要的调用方打开 [SourceSyncDeliveryOptions.Confirming]，把 Ok 当作「已被接收」之前先看它有没有确认。
 */
object SourceSyncDeliveryReasons {

    /** 帧写完了，但对端没在预算内关连接，或等它关闭时读失败。关闭是单向帧协议唯一的送达信号。 */
    const val DELIVERY_UNCONFIRMED: String = "delivery-unconfirmed"

    /** 全部码，顺序与 Windows 的 All 列表一致（顺序本身被测试钉住，用于发现单侧增删）。 */
    val ALL: List<String> = listOf(DELIVERY_UNCONFIRMED)
}

/**
 * 等对端关闭的那一眼看到了什么（与 Windows SourceSyncDeliveryResult 同义）。
 *
 * 只有 [Confirmed] 是「对端读到末尾」的证据；[Unconfirmed] 一定带 [SourceSyncDeliveryReasons] 里的码。
 */
sealed interface SourceSyncDeliveryResult {

    /**
     * 对端关了连接：它读到了末尾。
     *
     * @param bytesRead 对端在信封之后发来的字节数，正常为 0。
     * @param elapsedMs 这次等待实际花了多久，便于日志写「confirmed after N ms」。
     */
    class Confirmed(val bytesRead: Int, val elapsedMs: Long) : SourceSyncDeliveryResult

    /**
     * 没确认：[reason] 是 [SourceSyncDeliveryReasons] 里的码，[detail] 只带字节数与底层异常类名，
     * 永远不带信封内容或口令。
     */
    class Unconfirmed(
        val reason: String,
        val detail: String,
        val elapsedMs: Long,
    ) : SourceSyncDeliveryResult
}

/**
 * 一次发送要不要等对端关闭，等多久（对应 Windows SourceSyncDeliveryOptions）。
 *
 * 默认 [Off]，而 Off 就是今天的行为：不多写一个字节、不多读一次、不多等一毫秒。
 * 确认依赖「接收端拿全信封后关连接」这条事实（Windows 端点会先半关再收尾，见 W6-26 第 8 节）；
 * Android 接收端是否立刻关闭没有实测，所以等待是可选而不是默认。
 */
sealed class SourceSyncDeliveryOptions {

    /** 是否等对端关闭。 */
    abstract val confirm: Boolean

    /** 等待预算，毫秒（只有 [Confirming] 真的用它）。 */
    abstract val budgetMs: Int

    /** 默认：写完就返回，不等。 */
    object Off : SourceSyncDeliveryOptions() {
        override val confirm: Boolean get() = false
        override val budgetMs: Int get() = 0
        override fun toString(): String = "SourceSyncDeliveryOptions { Confirm = false }"
    }

    /** 等对端关闭；预算非正时按 [DEFAULT_BUDGET_MS] 处理（与 Windows Confirming 同口径）。 */
    class Confirming(override val budgetMs: Int = DEFAULT_BUDGET_MS) : SourceSyncDeliveryOptions() {
        override val confirm: Boolean get() = true
        override fun toString(): String =
            "SourceSyncDeliveryOptions { Confirm = true, BudgetMs = " + budgetMs + " }"
    }

    companion object {

        /** 默认等待预算：2000 毫秒（与 Windows DefaultBudgetMilliseconds 一致）。 */
        const val DEFAULT_BUDGET_MS: Int = 2_000

        /** 等对端关闭；budgetMs 为 null 或非正时取 [DEFAULT_BUDGET_MS]。 */
        fun confirming(budgetMs: Int? = null): Confirming =
            Confirming(if (budgetMs == null || budgetMs <= 0) DEFAULT_BUDGET_MS else budgetMs)

        /** 布尔开关到选项：false 一律 Off，避免调用方自己拼 null。 */
        fun confirmingOrOff(enabled: Boolean, budgetMs: Int? = null): SourceSyncDeliveryOptions =
            if (enabled) confirming(budgetMs) else Off
    }
}

/**
 * 投递确认唯一读写点（对应 Windows SourceSyncDeliveryConfirmation）。
 *
 * 语义：帧全部 flush 之后，在预算内读这条连接的输入流直到对端关连接（read 返回 -1）。
 * 读到 FIN 才算确认；超时、对端还在发、读失败都折成 [SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED]，
 * 且**从不抛异常**——调用方拿到的永远是两种结果之一。
 *
 * 为什么不是「再发一个确认帧」：单向帧协议里没有第二个方向的信封可以承载它，
 * 而接收端关连接本身就是它读完并处理完的证据（Windows 端点先半关再收尾）。
 */
object SourceSyncDeliveryConfirmation {

    /**
     * 对端在信封之后最多能发多少字节。
     *
     * 回一封信封的对端不是在确认任何事；不设上限就等于让它把发送端拖到预算耗尽。
     */
    const val MAXIMUM_DRAIN_BYTES: Int = 64 * 1024

    /** 每次读的暂存区大小（与 Windows 的 256 字节一致）。 */
    private const val SCRATCH_BYTES: Int = 256

    /**
     * 等对端关闭这条连接。
     *
     * @param input 刚写完帧的那条连接的输入流；调用方仍持有它。
     * @param sentBytes 已经写出的信封字节数，只用在失败 detail 里。
     * @param budgetMs 等待预算；非正时按 [SourceSyncDeliveryOptions.DEFAULT_BUDGET_MS]。
     * @param setReadTimeout 把「剩余预算」落到当前活跃 socket 的 soTimeout 上；为 null 时靠流自身的超时。
     *   不自己起线程：把预算设成读超时，「对端静默」与「对端关了」才分得开。
     */
    fun confirm(
        input: InputStream,
        sentBytes: Int,
        budgetMs: Int = SourceSyncDeliveryOptions.DEFAULT_BUDGET_MS,
        setReadTimeout: ((Int) -> Unit)? = null,
    ): SourceSyncDeliveryResult {
        val limit = if (budgetMs <= 0) SourceSyncDeliveryOptions.DEFAULT_BUDGET_MS else budgetMs
        val startedAt = System.nanoTime()
        val deadline = startedAt + limit.toLong() * 1_000_000L
        val scratch = ByteArray(SCRATCH_BYTES)
        var drained = 0
        try {
            while (true) {
                if (setReadTimeout != null) {
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                    val slice = if (remainingMs > 0L) remainingMs.toInt() else 1
                    try {
                        setReadTimeout(slice)
                    } catch (error: Exception) {
                        return unconfirmed(
                            sentBytes = sentBytes,
                            startedAt = startedAt,
                            detail = "信封已写出（" + sentBytes + " 字节），但等对端关闭的读超时设不上" +
                                "（" + error.javaClass.simpleName + "），所以这次发送没有送达证据。",
                        )
                    }
                }
                val got = input.read(scratch)
                if (got < 0) {
                    return SourceSyncDeliveryResult.Confirmed(
                        bytesRead = drained,
                        elapsedMs = elapsedSince(startedAt),
                    )
                }
                drained += got
                if (drained > MAXIMUM_DRAIN_BYTES) {
                    return unconfirmed(
                        sentBytes = sentBytes,
                        startedAt = startedAt,
                        detail = "信封已写出（" + sentBytes + " 字节），但对端在一封信封之后还在发" +
                            "（已读 " + drained + " 字节）：回一发信封的对端不是在确认收到，" +
                            "所以投递仍未确认。",
                    )
                }
            }
        } catch (timeout: SocketTimeoutException) {
            return unconfirmed(
                sentBytes = sentBytes,
                startedAt = startedAt,
                detail = "信封已写出（" + sentBytes + " 字节），但对端没有在 " + limit +
                    " 毫秒内关闭连接，所以这次发送不是送达证据。",
            )
        } catch (error: IOException) {
            return unconfirmed(
                sentBytes = sentBytes,
                startedAt = startedAt,
                detail = "信封已写出（" + sentBytes + " 字节），但等对端关闭时连接出了问题：" +
                    error.javaClass.simpleName + "。",
            )
        } catch (error: Exception) {
            return unconfirmed(
                sentBytes = sentBytes,
                startedAt = startedAt,
                detail = "信封已写出（" + sentBytes + " 字节），但等对端关闭时读失败：" +
                    error.javaClass.simpleName + "。",
            )
        }
    }

    private fun unconfirmed(
        sentBytes: Int,
        startedAt: Long,
        detail: String,
    ): SourceSyncDeliveryResult.Unconfirmed = SourceSyncDeliveryResult.Unconfirmed(
        reason = SourceSyncDeliveryReasons.DELIVERY_UNCONFIRMED,
        detail = detail,
        elapsedMs = elapsedSince(startedAt),
    )

    private fun elapsedSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
