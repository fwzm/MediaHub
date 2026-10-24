package com.mediahub.feature.settings.sync

import com.mediahub.core.database.sync.SourceSyncTransferImportOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「从 Windows 接收」区块的四种状态（W6-27）。
 *
 * 刻意做成密封接口而不是「布尔 + 字符串」：三态结果各自带自己的稳定码与诊断，
 * 界面上同一时刻只有一种结果行，切换状态时旧码自动消失（不存在"上一条失败码还挂着"的中间态）。
 */
sealed interface SourceSyncReceiveState {

    /** 还没点过接收。 */
    data object Idle : SourceSyncReceiveState

    /** 正在接收：按钮禁用，重复点击不会再发一次。 */
    data object Receiving : SourceSyncReceiveState

    /** 信封已收下并交给导入页（**没有**预览、**没有**写库）。 */
    data class Received(val deviceId: String?, val fileName: String) : SourceSyncReceiveState

    /** 帧层成功但载荷不是合法信封（码来自 SourceSyncCodec）。 */
    data class Rejected(val reason: String, val message: String?) : SourceSyncReceiveState

    /** 帧层失败（码来自 SourceSyncTransferReasons：not-loopback / connect-failed / ...）。 */
    data class Failed(val reason: String, val detail: String?) : SourceSyncReceiveState
}

/** 接收区块的固定文案（与界面、测试、文档共用一份）。 */
object SourceSyncReceiveCopy {

    const val TITLE: String = "从 Windows 接收"

    const val HINT: String = "在 Windows 端点『发送』后，填它显示的地址与端口；接收只读入信封，不写库。"

    const val HOST_LABEL: String = "地址（默认回环）"

    const val PORT_LABEL: String = "端口（必填）"

    const val CHALLENGE_LABEL: String = "挑战（可留空）"

    const val ACTION: String = "接收"

    const val BUSY: String = "接收中…"

    const val PORT_REQUIRED: String = "端口必须是 1-65535 的数字，填好才能接收。"

    /** 收到之后必须明确"下一步是预览"，否则用户会以为已经导入了。 */
    const val RECEIVED_HINT: String = "已收下信封，请点『预览』查看条目；写入仍要逐条允许后再应用。"

    /** 一条结果行（Idle 与 Receiving 没有结果行）。 */
    fun lineOf(state: SourceSyncReceiveState): String? = when (state) {
        SourceSyncReceiveState.Idle, SourceSyncReceiveState.Receiving -> null
        is SourceSyncReceiveState.Received -> receivedText(state)
        is SourceSyncReceiveState.Rejected -> "信封被拒：" + state.reason +
            (state.message?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: "")
        is SourceSyncReceiveState.Failed -> "接收失败：" + state.reason +
            (state.detail?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: "")
    }

    private fun receivedText(state: SourceSyncReceiveState.Received): String {
        val device = state.deviceId?.trim().orEmpty()
        val head = if (device.isEmpty()) {
            "已收下信封（设备未知）。"
        } else {
            "已收下信封（设备：" + device + "）。"
        }
        return head + RECEIVED_HINT
    }

    /** 结果行的稳定码（Received 为空），供 UI 测试与排障直接断言。 */
    fun codeOf(state: SourceSyncReceiveState): String? = when (state) {
        SourceSyncReceiveState.Idle, SourceSyncReceiveState.Receiving -> null
        is SourceSyncReceiveState.Received -> null
        is SourceSyncReceiveState.Rejected -> state.reason
        is SourceSyncReceiveState.Failed -> state.reason
    }
}

/**
 * 接收表单（纯数据，界面与单测共用）。
 *
 * 只校验**端口**：host 留空回落环、challenge 留空表示"对端不要求挑战"。
 * 非回环地址**故意不在这里拦**——那是传输客户端的判定（not-loopback），界面只显示它的码，
 * 若这里再判一次，同一个错误就会有两个来源、两种说法。
 */
data class SourceSyncReceiveForm(
    val host: String = DEFAULT_HOST,
    val port: String = "",
    val challenge: String = "",
) {

    /** 留空即回环：既是最常用的联调地址，也是唯一不会被传输层拒绝的地址。 */
    val hostOrLoopback: String get() = host.trim().ifEmpty { DEFAULT_HOST }

    /** 空串、非数字、越界一律 null（null 表示"填得不合法，不能提交"）。 */
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in VALID_PORTS }

    /** 空挑战 = 对端不要求挑战（与 W6-22 传输契约一致）。 */
    val challengeOrNull: String? get() = challenge.trim().takeIf { it.isNotEmpty() }

    val canSubmit: Boolean get() = portNumber != null

    companion object {
        const val DEFAULT_HOST: String = "127.0.0.1"

        val VALID_PORTS: IntRange = 1..65535
    }
}

/**
 * 传输端口：让界面测试不拨号、不起 socket（真 socket 路径由 core:database 的 W6-23 测试钉住）。
 */
fun interface SourceSyncReceivePort {
    suspend fun receive(host: String, port: Int, expectedChallenge: String?): SourceSyncTransferImportOutcome
}

/**
 * 「从 Windows 接收」的状态机（纯 Kotlin，W6-27）。
 *
 * 三条不可让步的规矩，全部在这里而不是在 Compose 里：
 * 1. **失败零写入**：只有 Imported 且网关没有整封拒绝时才调用 [onEnvelope]；
 *    传输失败 / 信封被拒都只更新本区块的状态，session 里已加载的信封、预览与逐条决策一个字都不动。
 * 2. **不自动预览、不自动应用**：收到之后只把信封交给导入页，用户还要自己点预览、点应用。
 * 3. **不吞码**：reason 原样保留（not-loopback / connect-failed / auth-mismatch / malformed-json ...），
 *    界面按原码显示，不折成布尔、不换成自己的说法。
 */
class SourceSyncReceiveController(
    private val port: SourceSyncReceivePort,
    private val onEnvelope: (envelopeJson: String, fileName: String) -> Unit,
) {

    private val _state = MutableStateFlow<SourceSyncReceiveState>(SourceSyncReceiveState.Idle)

    val state: StateFlow<SourceSyncReceiveState> = _state.asStateFlow()

    /** 单飞闸：一次只允许一条接收在飞（重复点击只发一次，见 duplicateTapSendsOnlyOneReceive）。 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 发起一次接收。
     *
     * @return true 表示本次真的调用了一次端口；false 表示端口不合法（不提交）或已有一次在飞。
     */
    suspend fun receive(form: SourceSyncReceiveForm): Boolean {
        val targetPort = form.portNumber ?: return false
        if (!inFlight.compareAndSet(false, true)) return false
        try {
            _state.value = SourceSyncReceiveState.Receiving
            val outcome = try {
                port.receive(form.hostOrLoopback, targetPort, form.challengeOrNull)
            } catch (cancellation: CancellationException) {
                _state.value = SourceSyncReceiveState.Idle
                throw cancellation
            } catch (unexpected: Exception) {
                // 正常路径不会到这里：core:database 已把 IO 失败映射成稳定码。
                // 端口实现坏了也不能把异常抛给界面线程，因此给一个 Android 本地兜底码并保留异常类型。
                _state.value = SourceSyncReceiveState.Failed(
                    reason = RECEIVE_FAILED,
                    detail = unexpected::class.java.simpleName,
                )
                return true
            }
            apply(outcome)
            return true
        } finally {
            inFlight.set(false)
        }
    }

    /** 把用户手动选文件 / 重置之后的旧结论清掉（不清的话界面上会挂着上一次的接收结果）。 */
    fun clear() {
        _state.value = SourceSyncReceiveState.Idle
    }

    private fun apply(outcome: SourceSyncTransferImportOutcome) {
        when (outcome) {
            is SourceSyncTransferImportOutcome.TransferFailed ->
                _state.value = SourceSyncReceiveState.Failed(outcome.reason, outcome.detail)

            is SourceSyncTransferImportOutcome.EnvelopeRejected ->
                _state.value = SourceSyncReceiveState.Rejected(outcome.reason, outcome.message)

            is SourceSyncTransferImportOutcome.Imported -> {
                val reason = outcome.result.rejectedReason
                if (!reason.isNullOrBlank()) {
                    // 网关整封拒绝（例如信封内重复 syncSourceId）：同样零写入、不碰 session。
                    _state.value = SourceSyncReceiveState.Rejected(reason, outcome.result.rejectedMessage)
                    return
                }
                val envelopeJson = outcome.envelopeJson
                if (envelopeJson.isNullOrBlank()) {
                    _state.value = SourceSyncReceiveState.Failed(RECEIVE_FAILED, MISSING_ENVELOPE)
                    return
                }
                onEnvelope(envelopeJson, TRANSFER_FILE_NAME)
                _state.value = SourceSyncReceiveState.Received(outcome.result.deviceId, TRANSFER_FILE_NAME)
            }
        }
    }

    companion object {

        /** 收到的信封在导入页里的文件名（与手选文件区分：一眼能看出它来自传输）。 */
        const val TRANSFER_FILE_NAME: String = "windows-transfer.envelope.json"

        /**
         * Android 本地的兜底码：接收端口意外抛异常、或 Imported 却没带信封原文时使用。
         * 不在线上格式词汇里，正常路径也不会出现（core:database 已把 IO 失败映射成稳定码）。
         */
        const val RECEIVE_FAILED: String = "receive-failed"

        const val MISSING_ENVELOPE: String = "missing-envelope"
    }
}

/**
 * 生产端口：core:database 的 W6-23 服务 + 只接收网关。
 *
 * 网关刻意用 SourceSyncImportGateways.acceptOnly()：接收阶段若接 AndroidSourceSyncService，
 * 就等于绕过预览与逐条允许直接写库。写入仍只经 SourceSyncImportSession.apply()。
 */
class SourceSyncTransferReceivePort(
    private val service: com.mediahub.core.database.sync.SourceSyncTransferImportService =
        com.mediahub.core.database.sync.SourceSyncTransferImportService(
            com.mediahub.core.database.sync.SourceSyncImportGateways.acceptOnly(),
        ),
) : SourceSyncReceivePort {

    override suspend fun receive(
        host: String,
        port: Int,
        expectedChallenge: String?,
    ): SourceSyncTransferImportOutcome = service.receiveAndImport(host, port, expectedChallenge)
}
