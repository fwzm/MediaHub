package com.mediahub.feature.settings.paired

import com.mediahub.core.common.pairing.FilePairedDeviceStore
import com.mediahub.core.common.pairing.PairedDeviceListPresenter
import com.mediahub.core.common.pairing.PairedDeviceListView
import com.mediahub.core.common.pairing.PairedDeviceReasons
import com.mediahub.core.common.pairing.PairedDeviceRegistry
import com.mediahub.core.common.pairing.PairedDeviceRow
import com.mediahub.core.common.pairing.PairedDeviceStore
import com.mediahub.core.common.pairing.PairedDeviceStoreReasons
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.common.sync.WireText
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 本机配对登记档案在 Android 上的落点（W6-70）。
 *
 * 目录名按 Android 惯例用下划线，**文件名与 Windows 逐字相同**（paired-devices.json），
 * 这样同一份登记档案的解释权只在 core:common 的 [com.mediahub.core.common.pairing.FilePairedDeviceStore] 里，
 * 本层只负责告诉你放在哪。
 */
object PairedDeviceStorage {

    /** 应用私有目录下的子目录名（`filesDir/paired_devices/`）。 */
    const val DIRECTORY_NAME: String = "paired_devices"

    /** 档案目录；不存在时由存储实现自己建。 */
    fun directoryIn(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

    /** 生产用的文件存储（容量与两端缺省一致：32 台）。 */
    fun storeIn(filesDir: File): PairedDeviceStore = FilePairedDeviceStore(directoryIn(filesDir))
}

/** 清单区块的三态：还在读 / 读到了 / 读不出来。 */
sealed interface PairedDeviceListState {

    /** 还没读过（或正在读）。 */
    data object Loading : PairedDeviceListState

    /** 读到了；[loadError] 非空表示档案有问题（已隔离或只读），清单本身仍可显示。 */
    data class Ready(
        val view: PairedDeviceListView,
        val loadError: String? = null,
    ) : PairedDeviceListState

    /** 连读都读不了（没有存储出口、或打开档案时抛了 IO/安全异常）。 */
    data class Unavailable(val reason: String, val message: String? = null) : PairedDeviceListState
}

/** 「忘记这台设备？」对话框的内容（与 Windows SyncForgetPrompt 同义）。 */
data class PairedDeviceForgetPrompt(
    val deviceId: String,
    val displayName: String,
    val fingerprintLabel: String,
    val title: String = PairedDeviceCopy.FORGET_TITLE,
    val text: String,
    val confirmLabel: String = PairedDeviceCopy.FORGET_CONFIRM_LABEL,
    val cancelLabel: String = PairedDeviceCopy.FORGET_CANCEL_LABEL,
)

/** 忘记动作的回执（与 Windows SyncForgetOutcome 同义）。 */
data class PairedDeviceForgetOutcome(
    val forgotten: Boolean,
    val title: String,
    val message: String,
    val reason: String? = null,
    /** 该对端的同步基线是否也一并清掉了（没有存储出口、或基线写不动时为 false）。 */
    val baselineForgotten: Boolean = false,
)

/** 配对设备页的一份完整快照（列表 + 待确认的忘记 + 上一次回执）。 */
data class PairedDeviceUiState(
    val list: PairedDeviceListState = PairedDeviceListState.Loading,
    val prompt: PairedDeviceForgetPrompt? = null,
    val outcome: PairedDeviceForgetOutcome? = null,
    val forgetting: Boolean = false,
)

/** 配对设备页的固定文案：与 Windows `SyncPageCopy` / `PairedDevice` 的措辞逐字对齐。 */
object PairedDeviceCopy {

    const val TITLE: String = "已配对设备"

    const val HINT: String = "这里列出本机记住的配对登记（设备标识、指纹、首次配对与最后相见的时间）。" +
        "忘记一台只删除本机的登记与该对端的同步基线：来源、凭据和对端设备都不会被改动。"

    const val LOADING: String = "正在读本机的配对登记…"

    const val REFRESH: String = "重新读取"

    const val DISMISS: String = "知道了"

    const val FORGET_TITLE: String = "忘记这台设备？"

    const val FORGET_KEEPS_NOTE: String = "只删除本机的配对登记与该对端的同步基线，来源、凭据和对端设备都不会被改动；" +
        "下次配对会重新按首次配对处理。"

    const val FORGET_CONFIRM_LABEL: String = "忘记"

    const val FORGET_CANCEL_LABEL: String = "取消"

    const val FORGET_DONE_TITLE: String = "已忘记该设备"

    const val FORGET_DONE_MESSAGE: String = "只删除了本机的配对登记与该对端的同步基线：来源、凭据和对端设备都没有变化；" +
        "下次配对会重新按首次配对处理。"

    /** 没有基线存储出口时的回执正文：登记删了，但基线可能还留在盘上。 */
    const val FORGET_DONE_MESSAGE_WITHOUT_BASELINE: String =
        "只删除了本机的配对登记：这个页面没有可用的同步基线存储，对端基线可能还留在盘上；来源、凭据和对端设备都没有变化。"

    const val FORGET_UNKNOWN_TITLE: String = "本机没有这台设备的登记"

    const val FORGET_NOTHING_PENDING: String = "没有待确认的忘记操作。"

    const val FORGET_CANCELLED: String = "没有删除任何登记。"

    /** 对话框正文：先点名是哪一台，再解释删了什么、没删什么。 */
    fun forgetText(displayName: String, fingerprintLabel: String): String =
        "将忘记「" + displayName + "」（" + fingerprintLabel + "）。" + FORGET_KEEPS_NOTE

    /** 档案有问题时的一句话解释；不认识的码原样带上，不吞掉。 */
    fun warningOf(reason: String): String = when (reason) {
        PairedDeviceStoreReasons.MALFORMED_JSON -> "本机配对登记档案读不懂，已隔离为备份，清单按空显示。"
        PairedDeviceStoreReasons.UNSUPPORTED_VERSION -> "本机配对登记档案的版本比本应用新，已隔离为备份，清单按空显示。"
        PairedDeviceStoreReasons.STORE_UNREADABLE -> "本机配对登记档案当前读不出来，清单按空显示，也不会写入新的配对。"
        PairedDeviceStoreReasons.LIMIT_REACHED -> "本机配对登记已达上限，新设备不会被登记。"
        PairedDeviceStoreReasons.WRITE_FAILED -> "本机配对登记写不进去，这次的改动没有落盘。"
        else -> "本机配对登记档案有问题（" + reason + "），清单可能不完整。"
    }

    /** 没有存储出口时的补充说明（预览态或未接线；不是档案坏了）。 */
    const val STORAGE_UNAVAILABLE_HINT: String =
        "这个页面当前没有可用的存储出口（预览态或未接线），没有读取本机登记。"

    /** 连档案都打不开时的说明。 */
    fun unavailableText(state: PairedDeviceListState.Unavailable): String =
        "本机配对登记当前不可用：" + state.reason +
            (state.message?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: "")

    /** 回执行：标题 + 正文，界面上一行读完。 */
    fun outcomeText(outcome: PairedDeviceForgetOutcome): String = outcome.title + "：" + outcome.message
}

/**
 * 配对设备页的控制器（W6-70：只读清单 + 忘记一台，纯 Kotlin）。
 *
 * 与 Windows `SyncPairingPresenter` 的分工一致：判定全部在 core:common 的 [PairedDeviceRegistry] 里，
 * 这里只做三件事——把档案读到界面上、把「忘记」拆成两段（先问、再删）、把结果写成回执。
 * 存储通过 [storeFactory] 注入：生产实现是 filesDir 下的 paired-devices.json，测试可以给内存实现。
 */
class PairedDeviceController(
    private val storeFactory: (() -> PairedDeviceStore)? = null,
    private val capacity: Int = PairedDeviceRegistry.DEFAULT_CAPACITY,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 该页的同步基线存储出口（W6-80）：删登记的同时忘掉「对端到哪儿了」。
     *
     * 传 null 表示这一层没有接线（预览态/测试），回执里 [PairedDeviceForgetOutcome.baselineForgotten] 恒为 false。
     */
    private val baselineStore: ISourceSyncBaselineStore? = null,
) {

    private val _state = MutableStateFlow(PairedDeviceUiState())

    val state: StateFlow<PairedDeviceUiState> = _state.asStateFlow()

    private val refreshing = AtomicBoolean(false)
    private val forgetting = AtomicBoolean(false)

    /** 懒建的档案句柄：第一次读盘时才打开，读失败不缓存失败（下次刷新还会再试）。 */
    private var registry: PairedDeviceRegistry? = null

    /**
     * 读一次本机登记档案并刷新清单。
     *
     * @return true 表示读到了（状态为 [PairedDeviceListState.Ready]）；
     *   false 表示没有存储出口、已有一次在读、或档案打不开（状态为 Unavailable）。
     */
    suspend fun refresh(): Boolean {
        val factory = storeFactory
        if (factory == null) {
            _state.update {
                it.copy(
                    list = PairedDeviceListState.Unavailable(
                        STORAGE_UNAVAILABLE,
                        PairedDeviceCopy.warningOf(PairedDeviceStoreReasons.STORE_UNREADABLE),
                    ),
                )
            }
            return false
        }
        if (!refreshing.compareAndSet(false, true)) {
            return false
        }
        _state.update { it.copy(list = PairedDeviceListState.Loading) }
        try {
            val loaded = withContext(io) { loadOnce(factory) }
            _state.update { it.copy(list = loaded) }
            return loaded is PairedDeviceListState.Ready
        } finally {
            refreshing.set(false)
        }
    }

    private fun loadOnce(factory: () -> PairedDeviceStore): PairedDeviceListState = try {
        val active = registry ?: PairedDeviceRegistry(factory(), capacity).also { registry = it }
        PairedDeviceListState.Ready(
            view = PairedDeviceListPresenter.build(active.getAll(), active.capacity, now()),
            loadError = active.loadError,
        )
    } catch (exception: IOException) {
        PairedDeviceListState.Unavailable(STORAGE_UNAVAILABLE, exception.message)
    } catch (exception: RuntimeException) {
        // SecurityException 之类：读不到就如实说读不到，不让一个坏档案把页面搞崩。
        PairedDeviceListState.Unavailable(STORAGE_UNAVAILABLE, exception.message)
    }

    /**
     * 第一步：把「忘记」变成一次待确认的操作，**一个字节都不写**。
     *
     * 设备不在当前清单里（或标识是空白）就返回 false，不弹对话框。
     */
    fun askForget(deviceId: String?): Boolean {
        val key = WireText.trim(deviceId ?: "")
        if (key.isEmpty()) {
            return false
        }
        val ready = _state.value.list as? PairedDeviceListState.Ready ?: return false
        val row = ready.view.rows.firstOrNull { WireText.equalsAsciiIgnoreCase(it.deviceId, key) } ?: return false
        _state.update { current ->
            current.copy(
                prompt = PairedDeviceForgetPrompt(
                    deviceId = row.deviceId,
                    displayName = row.displayName,
                    fingerprintLabel = row.fingerprintLabel,
                    text = PairedDeviceCopy.forgetText(row.displayName, row.fingerprintLabel),
                ),
                outcome = null,
            )
        }
        return true
    }

    /** 第二步之一：取消。本身也是一条回执（Windows 同义：取消也要说清「没有删除」）。 */
    fun cancelForget(): PairedDeviceForgetOutcome {
        val pending = _state.value.prompt
        val outcome = PairedDeviceForgetOutcome(
            forgotten = false,
            title = PairedDeviceCopy.FORGET_CANCEL_LABEL,
            message = if (pending != null) PairedDeviceCopy.FORGET_CANCELLED else PairedDeviceCopy.FORGET_NOTHING_PENDING,
        )
        _state.update { it.copy(prompt = null, outcome = outcome) }
        return outcome
    }

    /**
     * 第二步之二：确认忘记。写盘失败只算「本机没有这台设备的登记」，不抛。
     *
     * 删成功后会重新读一次清单，界面不会留下已经不在档案里的那一行。
     */
    suspend fun confirmForget(): PairedDeviceForgetOutcome {
        val pending = _state.value.prompt
        if (pending == null) {
            val outcome = PairedDeviceForgetOutcome(
                forgotten = false,
                title = PairedDeviceCopy.FORGET_UNKNOWN_TITLE,
                message = PairedDeviceCopy.FORGET_NOTHING_PENDING,
            )
            _state.update { it.copy(outcome = outcome) }
            return outcome
        }
        if (!forgetting.compareAndSet(false, true)) {
            return PairedDeviceForgetOutcome(
                forgotten = false,
                title = PairedDeviceCopy.FORGET_UNKNOWN_TITLE,
                message = PairedDeviceCopy.FORGET_NOTHING_PENDING,
            )
        }
        _state.update { it.copy(prompt = null, forgetting = true) }
        try {
            // 先删登记、再清基线：顺序反过来会在中途留下「登记还在、基线没了」的假首轮状态。
            val outcomePair = withContext(io) {
                val active = registry
                val deleted = active != null && active.forget(pending.deviceId)
                deleted to (if (deleted) forgetBaseline(pending.deviceId) else false)
            }
            val forgotten = outcomePair.first
            val baselineForgotten = outcomePair.second
            val outcome = if (forgotten) {
                PairedDeviceForgetOutcome(
                    forgotten = true,
                    title = PairedDeviceCopy.FORGET_DONE_TITLE,
                    message = if (baselineForgotten) {
                        PairedDeviceCopy.FORGET_DONE_MESSAGE
                    } else {
                        PairedDeviceCopy.FORGET_DONE_MESSAGE_WITHOUT_BASELINE
                    },
                    reason = PairedDeviceReasons.FORGOTTEN,
                    baselineForgotten = baselineForgotten,
                )
            } else {
                PairedDeviceForgetOutcome(
                    forgotten = false,
                    title = PairedDeviceCopy.FORGET_UNKNOWN_TITLE,
                    message = PairedDeviceRegistry.describeReason(PairedDeviceReasons.UNKNOWN_DEVICE),
                    reason = PairedDeviceReasons.UNKNOWN_DEVICE,
                )
            }
            _state.update { it.copy(forgetting = false, outcome = outcome) }
            if (forgotten) {
                refresh()
            }
            return outcome
        } finally {
            forgetting.set(false)
            _state.update { it.copy(forgetting = false) }
        }
    }

    /**
     * 清掉本机记着的「这个对端同步到哪儿了」（W6-80）。
     *
     * 配对登记已经删掉之后才调用；清不动（没有存储出口、磁盘写失败、档案坏了）只返回 false，
     * **不**把设备变回「还在」，也不吞协程取消。
     */
    private fun forgetBaseline(deviceId: String): Boolean {
        val store = baselineStore ?: return false
        return try {
            store.forget(deviceId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
    }

    /** 收掉上一次回执（界面上的「知道了」）。 */
    fun dismissOutcome() {
        _state.update { it.copy(outcome = null) }
    }

    /** 换页时清掉全部结论（清单回到未读）。 */
    fun clear() {
        _state.value = PairedDeviceUiState()
    }

    companion object {

        /** 本次构建没有可用的存储出口（界面预览态或未接线）。本层前置条件码，不进 core 词汇表。 */
        const val STORAGE_UNAVAILABLE: String = "paired-device-storage-unavailable"
    }
}
