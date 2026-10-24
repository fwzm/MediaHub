package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncReceiptOptions
import com.mediahub.core.common.sync.SourceSyncTlsIdentity
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTlsReasons
import com.mediahub.core.common.sync.SourceSyncTransferListener
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.database.sync.SourceSyncImportGateway
import com.mediahub.core.database.sync.SourceSyncImportGateways
import com.mediahub.core.database.sync.SourceSyncListenerImportService
import com.mediahub.core.database.sync.SourceSyncListenerImportStart
import com.mediahub.core.database.sync.SourceSyncTransferImportOutcome
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * W6-36：手机当接收端（监听）的六种状态。
 *
 * 与 W6-27 的 [SourceSyncReceiveState] 同一条设计：密封接口而不是「布尔 + 字符串」，
 * 每个终态只带自己的稳定码与诊断，切状态时旧码自动消失；监听本身的开着或关着由
 * [SourceSyncListenerController.isListening] 单独表达——收到一封信封之后监听还在开，
 * 用户可以直接等第二次发送，不必重新开端口。
 */
sealed interface SourceSyncListenState {

    /** 还没开监听。 */
    data object Idle : SourceSyncListenState

    /** 监听已开，等对端连上来；[host] 是实际绑定的地址（缺省回环，W6-52 可能是显式单播）。 */
    data class Listening(
        val port: Int,
        val tls: Boolean,
        val certificateFingerprint: String? = null,
        val host: String = SourceSyncTransferListener.DEFAULT_HOST,
    ) : SourceSyncListenState

    /** 正在等一次发送（accept 与读帧都在 IO 线程上，这里只是状态）。 */
    data class Receiving(val port: Int) : SourceSyncListenState

    /** 信封已收下并交给导入页（**没有**预览、**没有**写库）。 */
    data class Received(
        val port: Int,
        val deviceId: String?,
        val fileName: String,
        /** 本端是否真的给对端写回了 0x04 回执（W6-40）；默认关回执时为 false。 */
        val receiptWritten: Boolean = false,
    ) : SourceSyncListenState

    /** 帧层成功但载荷不是合法信封（码来自 SourceSyncCodec）。 */
    data class Rejected(
        val port: Int,
        val reason: String,
        val message: String?,
    ) : SourceSyncListenState

    /** 任意一步失败：开监听失败时 port 为 null，收的时候失败则带上当时的端口。 */
    data class Failed(
        val port: Int?,
        val code: String,
        val detail: String?,
    ) : SourceSyncListenState
}

/** 监听区块的固定文案（界面、测试与文档共用一份）。 */
object SourceSyncListenCopy {

    const val TITLE: String = "接收（监听）"

    const val HINT: String =
        "先在本机开一个监听（缺省只绑回环，跨机要先填地址并点亮开关），再在 Windows 端点用 transfer send 发过来；" +
            "收到的信封只进预览，写入仍要逐条允许后再应用。"

    const val HOST_LABEL: String = "绑定地址（留空=127.0.0.1）"

    /** W6-52：非回环只在用户自己写下地址并且点亮这个开关时才可能被接受。 */
    const val ALLOW_HOST_LABEL: String = "允许绑一个非回环的单播地址（跨机接收）"

    /** 开关的边界如实写清楚：只收「一台机器的地址」，其余全部在建 socket 之前拒绝。 */
    const val ALLOW_HOST_HINT: String =
        "只有在这个框里写下一个单播 IP（例如 192.168.1.20）时才会被接受；" +
            "通配、广播、组播地址与主机名一律在建 socket 之前拒绝，码仍是 not-loopback。"

    const val PORT_LABEL: String = "端口（留空=系统分配）"

    const val TLS_LABEL: String = "用 TLS 1.3 接收（需要本机身份证书）"

    /** 收到能解码的信封后回一个裸 0x04（W6-40）：这是对端唯一能拿到的「收下了」证据。 */
    const val RECEIPT_LABEL: String = "收下后回 0x04 回执"

    /** 回执的边界如实写清楚：解码成功才回，解不出的载荷保持沉默（对端看到 receipt-missing）。 */
    const val RECEIPT_HINT: String =
        "只有信封解码成功才回一个裸 0x04；解不出的载荷保持沉默，对端会看到 receipt-missing。"

    const val START: String = "开始监听"

    const val STOP: String = "停止监听"

    const val RECEIVE_ONE: String = "等一次发送"

    const val BUSY: String = "接收中…"

    const val PORT_INVALID: String = "端口必须是 1-65535 的数字，或者留空让系统分配。"

    /** 收到之后必须明确「下一步是预览」，否则用户会以为已经导入了。 */
    const val RECEIVED_HINT: String = "已收下信封，请点『预览』查看条目；写入仍要逐条允许后再应用。"

    /** 本机没有可用身份证书时的如实说明：不假装支持 TLS，也不给一个点了会失败的开关。 */
    const val TLS_UNAVAILABLE: String =
        "本机没有可用的身份证书（未配置 KeyStore），无法用 TLS 接收，只能明文。" +
            "这不是设置问题：证书下发还没做，界面不会假装支持。"

    const val TLS_AVAILABLE_HINT: String =
        "TLS 已可用：开监听后把下面这行的指纹交给 Windows，发送时要带 --tls --expect-fingerprint。"

    /** 回执写出去了才追加的一句（W6-40）：对端现在能区分「接受」与「收完就断」。 */
    const val RECEIPT_WRITTEN_HINT: String = "已回执 0x04，对端能确认这封信封被收下了。"

    /** W6-53：要求对端在 tag 帧里带口令（HMAC-SHA256），口令只留在内存。 */
    const val AUTH_LABEL: String = "要求对端带口令（HMAC 认证）"

    /** 口令框只在开关点亮时可用，输入内容按密码处理。 */
    const val SECRET_LABEL: String = "口令（只留在内存）"

    /** 开关的边界如实写清楚：对端没带 tag 判 auth-required，tag 对不上判 auth-mismatch。 */
    const val AUTH_HINT: String =
        "开了之后，对端必须在 tag 帧里带同一口令的 HMAC，对不上判 auth-mismatch；" +
            "对端根本没带认证则判 auth-required。口令只进内存，不进状态行、日志与导出。"

    /** 开了认证却没填口令时的提示：连 socket 都不开（与 W6-42 发送端同一口径）。 */
    const val SECRET_REQUIRED: String =
        "开了认证就必须填口令：没有口令时对端带来的 tag 帧一律判 auth-secret-unavailable。"

    /** W6-54：等待预算（毫秒）的标签，与 Windows 端 transfer serve --budget-ms 同一个词。 */
    const val BUDGET_LABEL: String = "等待预算（毫秒，留空=15000）"

    /**
     * W6-54：一个框管两段等待，原因写在文案里而不是让用户猜。
     *
     * core 的监听有两个超时：等第一条连接（SO_TIMEOUT on accept）与连上之后每次读（SO_TIMEOUT on read）。
     * 后者是单次读的上限、不是整场传输的总预算，所以两段用同一个数字是安全的：
     * 值太小会让大信封提前断，值太大只是失败得慢一点。
     */
    const val BUDGET_HINT: String =
        "一个框管两段等待：等第一条连接最多多久，连上之后每次读最多等多久" +
            "（这是单次读的上限，不是整场传输的总预算）。范围 100..600000 毫秒，" +
            "与 Windows 端 transfer serve --budget-ms 同值同义。"

    /** 预算文本不合法时的提示：与端口非法同一姿势，连 socket 都不开。 */
    const val BUDGET_INVALID_HINT: String =
        "等待预算是 100..600000 毫秒之间的整数，留空表示 15000 毫秒；这个值没有被使用。"

    /** 一条状态行（Idle 没有状态行）。 */
    fun lineOf(state: SourceSyncListenState): String? = when (state) {
        SourceSyncListenState.Idle -> null
        is SourceSyncListenState.Listening ->
            "正在监听 " + state.host + ":" + state.port + tlsSuffix(state.tls) + "，等 Windows 发送…"
        is SourceSyncListenState.Receiving -> "接收中…（端口 " + state.port + "）"
        is SourceSyncListenState.Received -> receivedText(state)
        is SourceSyncListenState.Rejected ->
            "信封被拒：" + state.reason + detailSuffix(state.message)
        is SourceSyncListenState.Failed -> "失败：" + state.code + detailSuffix(state.detail)
    }

    /** 状态行的稳定码（Idle / Listening / Receiving / Received 为空），供界面测试与排障直接断言。 */
    fun codeOf(state: SourceSyncListenState): String? = when (state) {
        SourceSyncListenState.Idle,
        is SourceSyncListenState.Listening,
        is SourceSyncListenState.Receiving,
        is SourceSyncListenState.Received,
        -> null

        is SourceSyncListenState.Rejected -> state.reason
        is SourceSyncListenState.Failed -> state.code
    }

    /**
     * 端口行：只有 [SourceSyncListenState.Listening] 有（那时才确定知道端口、明文或 TLS、指纹）。
     *
     * 界面把它单独一行显示，用户要抄给 Windows 的就是这一个端口（TLS 时再加指纹）。
     */
    fun portLineOf(state: SourceSyncListenState): String? {
        if (state !is SourceSyncListenState.Listening) return null
        val head = "监听 " + state.host + ":" + state.port + tlsSuffix(state.tls)
        val pin = state.certificateFingerprint?.trim().orEmpty()
        return if (pin.isEmpty()) head else head + "，证书指纹：" + pin
    }

    private fun tlsSuffix(tls: Boolean): String = if (tls) "（TLS 1.3）" else "（明文）"

    private fun detailSuffix(detail: String?): String =
        detail?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" }.orEmpty()

    private fun receivedText(state: SourceSyncListenState.Received): String {
        val device = state.deviceId?.trim().orEmpty()
        val head = if (device.isEmpty()) {
            "已收下信封（设备未知）。"
        } else {
            "已收下信封（设备：" + device + "）。"
        }
        val receipt = if (state.receiptWritten) RECEIPT_WRITTEN_HINT else ""
        return head + RECEIVED_HINT + receipt
    }
}

/**
 * 监听表单（纯数据，界面与单测共用）。
 *
 * 端口留空 = 让系统分配（0），这是默认姿势：回环上随便挑一个固定端口最容易撞车。
 * 除了端口之外不做别的校验——TLS 能不能开由身份证书决定，代码是 tls-certificate-unavailable，
 * 不在界面里凭猜测拦下来。
 */
data class SourceSyncListenForm(
    val port: String = "",
    val tls: Boolean = false,
    /**
     * 收到一封能解码的信封后写一个 0x04 回执（W6-40），默认关。
     *
     * 关着时线上一个字节都不多写（与 W6-22 至今逐字节相同）；开着只影响「写不写回执」，
     * 不影响写库——写入仍然只经预览与逐条允许。
     */
    val receipt: Boolean = false,
    /**
     * 绑定的地址（W6-52）：留空 = 127.0.0.1，与之前逐字相同。
     *
     * 非回环地址只有在这个框里显式写出来、并且打开 [allowExplicitHost] 时才可能被接受；
     * 判定在 core（[com.mediahub.core.common.sync.SourceSyncHostAddress]），界面不自己猜。
     */
    val host: String = "",
    /** 是否允许绑一个显式单播地址（W6-52）；关着时只接受回环，拒绝码仍是 not-loopback。 */
    val allowExplicitHost: Boolean = false,
    /**
     * 是否要求对端带口令（W6-53）：开着时对端每一帧 tag 都要用同一口令算 HMAC-SHA256。
     *
     * 默认关，与 W6-25 之前逐字同行为；关着时一个认证字节都不多读。
     */
    val auth: Boolean = false,
    /** 口令原文（只在内存里用一次，不落盘、不进状态行）；[auth] 关着时忽略。 */
    val secret: String = "",
    /**
     * 等待预算（毫秒，W6-54）：留空 = 15000，与 W6-50 的 transfer serve 默认同值。
     *
     * 同一个数字既给「等第一条连接」也给「连上之后每次读」，理由见 [SourceSyncListenCopy.BUDGET_HINT]；
     * 非数字或越界不会去开 socket，状态留在本地码 listen-budget-invalid。
     */
    val budget: String = "",
) {

    /** 留空给 0（系统分配）；非数字、越界一律 null。 */
    val portNumber: Int?
        get() {
            val text = port.trim()
            if (text.isEmpty()) return SYSTEM_ASSIGNED_PORT
            return text.toIntOrNull()?.takeIf { it in VALID_PORTS }
        }

    val isPortTextAcceptable: Boolean get() = portNumber != null

    /** 开了认证就必须有口令：空口令等于自己把自己锁在门外。 */
    val isSecretAcceptable: Boolean get() = !auth || secret.isNotEmpty()

    /** 留空给缺省 15000 毫秒；非数字、越界一律 null（不开 socket）。 */
    val budgetMillis: Int?
        get() {
            val text = budget.trim()
            if (text.isEmpty()) return SourceSyncTransferListener.DEFAULT_ACCEPT_TIMEOUT_MS
            return text.toIntOrNull()?.takeIf { it in VALID_BUDGET_MILLIS }
        }

    val isBudgetTextAcceptable: Boolean get() = budgetMillis != null

    val canSubmit: Boolean get() = isPortTextAcceptable && isSecretAcceptable && isBudgetTextAcceptable

    /** 诊断 / 日志安全：口令原文一律不出现在 toString 里。 */
    override fun toString(): String = "SourceSyncListenForm(port=" + port +
        ", tls=" + tls +
        ", receipt=" + receipt +
        ", host=" + host +
        ", allowExplicitHost=" + allowExplicitHost +
        ", budget=" + budget +
        ", auth=" + auth +
        ", secret=<redacted>)"

    companion object {

        /** 0 表示让系统分配端口（与 ServerSocket 的语义一致）。 */
        const val SYSTEM_ASSIGNED_PORT: Int = 0

        val VALID_PORTS: IntRange = 1..65535

        /** W6-54：与 Windows 端 transfer serve --budget-ms 同一个范围（100..600000 毫秒）。 */
        val VALID_BUDGET_MILLIS: IntRange = 100..600_000
    }
}

/** TLS 能不能开：可用性 + 给用户看的一句实话。 */
data class SourceSyncListenTlsAvailability(
    val available: Boolean,
    val note: String,
)

/** 开监听的结果：要么拿到一个会话，要么一个稳定码。 */
sealed interface SourceSyncListenerOpenResult {

    class Opened(val session: SourceSyncListenerSession) : SourceSyncListenerOpenResult

    class Failed(val code: String, val detail: String? = null) : SourceSyncListenerOpenResult
}

/**
 * 一个已经开着的回环监听：端口、是否 TLS、指纹，以及「等一次发送」。
 *
 * 抽成接口是为了让 feature/settings 的单测不绑 socket（真 socket 路径由 core:common 的
 * SourceSyncTransferListenerTest 与 core:database 的 SourceSyncListenerImportTest 钉住）。
 */
interface SourceSyncListenerSession : Closeable {

    /**
     * 实际绑定的地址文本（W6-52）。
     *
     * 默认实现给缺省回环地址，既有测试替身（只关心端口与结果的）不必改一行；
     * 真实会话把它换成 listener.address，界面就能显示用户自己填的那个单播地址。
     */
    val address: String get() = SourceSyncTransferListener.DEFAULT_HOST

    /** 实际绑定的端口；开的时候给 0 也会在这里拿到系统分配的端口。 */
    val port: Int

    /** 是否 TLS 1.3。 */
    val tlsEnabled: Boolean

    /** 本端证书公钥指纹（小写 64 hex）；明文为 null。 */
    val certificateFingerprint: String?

    /** 等一条连接并收一封信封；三段结果与 W6-23 相同。 */
    suspend fun receiveOnce(): SourceSyncTransferImportOutcome
}

/** 开监听的入口（生产实现见 [SourceSyncListenerImportFactory]）。 */
interface SourceSyncListenerFactory {

    /** 界面据此决定 TLS 开关能不能点、旁边那句实话怎么写。 */
    fun tlsAvailability(): SourceSyncListenTlsAvailability

    /** 同步开监听（绑 socket），由控制器的 IO 上下文调用。 */
    fun open(port: Int, tls: Boolean): SourceSyncListenerOpenResult

    /**
     * W6-40：带回执开关的开监听。
     *
     * 默认实现忽略回执、等价于 [open]：既有工厂（含测试替身）不必改一行；
     * 支持回执的工厂覆盖这个方法即可。
     */
    fun open(port: Int, tls: Boolean, receipt: Boolean): SourceSyncListenerOpenResult = open(port, tls)

    /**
     * W6-52：带绑定地址与「是否允许显式单播」的开监听。
     *
     * 默认实现把地址丢掉、等价于 [open]：既有工厂（含测试替身）不必改一行；
     * 支持显式地址的工厂覆盖这个方法即可（生产实现见 [SourceSyncListenerImportFactory]）。
     */
    fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
    ): SourceSyncListenerOpenResult = open(port, tls, receipt)

    /**
     * W6-53：带对端口令要求的开监听。
     *
     * [auth] 为 null 表示不要求认证（与之前逐字同行为）；非空表示每一帧 tag 都要用这个口令验证。
     * 默认实现把 auth 丢掉、等价于上一个重载：既有工厂（含测试替身）不必改一行。
     */
    fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
        auth: SourceSyncTransferOptions?,
    ): SourceSyncListenerOpenResult = open(port, tls, receipt, host, allowExplicitHost)

    /**
     * W6-54：带等待预算（毫秒）的开监听。
     *
     * [budgetMillis] 既给「等第一条连接」也给「连上之后每次读」，缺省 15000 与 W6-53 之前逐字同行为。
     * 默认实现把预算丢掉、等价于上一个重载：既有工厂（含测试替身）不必改一行。
     */
    fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
        auth: SourceSyncTransferOptions?,
        budgetMillis: Int,
    ): SourceSyncListenerOpenResult = open(port, tls, receipt, host, allowExplicitHost, auth)
}

/**
 * 「接收（监听）」的状态机（纯 Kotlin，W6-36）。
 *
 * 五条不肯让步的规矩，全在这里而不是在 Compose 里：
 * 1. **只收不写**：网关固定是 SourceSyncImportGateways.acceptOnly()，收到信封只解码、不写库；
 *    只有 Imported 且网关没有整封拒绝时才调 [onEnvelope]，写入仍然只经 SourceSyncImportSession.apply()。
 * 2. **失败零写入**：传输失败 / 信封被拒都只改本区块的状态，session 里已加载的信封、预览与逐条决策一个字不动。
 * 3. **不自动预览、不自动应用**：收到之后只把信封交给导入页，用户还要自己点预览、点应用。
 * 4. **不吞码**：reason 原样保留（listener-failed / tls-handshake-failed / auth-mismatch / malformed-json ...）。
 * 5. **不假装支持 TLS**：没有可用身份证书时直接给 tls-certificate-unavailable，连 socket 都不开。
 */
class SourceSyncListenerController(
    private val factory: SourceSyncListenerFactory,
    private val onEnvelope: (envelopeJson: String, fileName: String) -> Unit,
) {

    private val _state = MutableStateFlow<SourceSyncListenState>(SourceSyncListenState.Idle)

    val state: StateFlow<SourceSyncListenState> = _state.asStateFlow()

    /** 监听是否开着（开着就能继续等下一次发送）。 */
    val isListening: Boolean get() = session != null

    /** TLS 可用性（每次读都取最新：证书可能在页面停留期间被装上）。 */
    val tlsAvailability: SourceSyncListenTlsAvailability get() = factory.tlsAvailability()

    /** 单飞闸：开监听与等一次发送各自一把（连点只发一次）。 */
    private val starting = AtomicBoolean(false)
    private val receiving = AtomicBoolean(false)

    /** 当前开着的监听；非空即 isListening。stop 时置空并 close。 */
    @Volatile
    private var session: SourceSyncListenerSession? = null

    /**
     * 开监听。
     *
     * @return true 表示监听现在真的开着；false 表示端口不合法 / TLS 不可用 / 已经开着 / 重复点击 / 开失败。
     *   端口不合法、TLS 不可用、开失败三种情况都会留下一个带稳定码的 [SourceSyncListenState.Failed]。
     */
    suspend fun start(form: SourceSyncListenForm): Boolean {
        val targetPort = form.portNumber
        if (targetPort == null) {
            _state.value = SourceSyncListenState.Failed(
                port = null,
                code = PORT_INVALID,
                detail = SourceSyncListenCopy.PORT_INVALID,
            )
            return false
        }
        if (session != null) return false
        if (form.tls && !tlsAvailability.available) {
            // 没证书就一个 socket 都不开：这是「不假装支持」的具体做法。
            _state.value = SourceSyncListenState.Failed(
                port = null,
                code = SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
                detail = tlsAvailability.note,
            )
            return false
        }
        if (!form.isSecretAcceptable) {
            // 开了认证却没有口令：一个 socket 都不开，码是本端的，不是线上码。
            _state.value = SourceSyncListenState.Failed(
                port = null,
                code = SECRET_MISSING,
                detail = SourceSyncListenCopy.SECRET_REQUIRED,
            )
            return false
        }
        val budgetMillis = form.budgetMillis
        if (budgetMillis == null) {
            // 预算文本不合法：同样一个 socket 都不开（W6-54），码是本端的，不是线上码。
            _state.value = SourceSyncListenState.Failed(
                port = null,
                code = BUDGET_INVALID,
                detail = SourceSyncListenCopy.BUDGET_INVALID_HINT,
            )
            return false
        }
        if (!starting.compareAndSet(false, true)) return false
        try {
            if (session != null) return false
            // 口令在这里换成 core 的选项：原文只活在这个局部变量里，进不了状态、日志与导出。
            val auth = if (form.auth) {
                SourceSyncTransferOptions.withSecret(form.secret.toByteArray(Charsets.UTF_8))
            } else {
                null
            }
            val opened = try {
                withContext(Dispatchers.IO) {
                    factory.open(
                        targetPort,
                        form.tls,
                        form.receipt,
                        form.host,
                        form.allowExplicitHost,
                        auth,
                        budgetMillis,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                // 正常路径不会到这里：core:database 已把 socket 失败映射成 listener-failed。
                _state.value = SourceSyncListenState.Failed(
                    port = null,
                    code = RECEIVE_FAILED,
                    detail = unexpected::class.java.simpleName,
                )
                return false
            }
            return when (opened) {
                is SourceSyncListenerOpenResult.Failed -> {
                    _state.value = SourceSyncListenState.Failed(null, opened.code, opened.detail)
                    false
                }

                is SourceSyncListenerOpenResult.Opened -> {
                    session = opened.session
                    _state.value = SourceSyncListenState.Listening(
                        port = opened.session.port,
                        tls = opened.session.tlsEnabled,
                        certificateFingerprint = opened.session.certificateFingerprint,
                        host = opened.session.address,
                    )
                    true
                }
            }
        } finally {
            starting.set(false)
        }
    }

    /**
     * 等一次发送并把它收下来。
     *
     * @return true 表示这次真的等过并拿到了一个结果（含失败与拒收）；false 表示没开监听或已有一次在等。
     */
    suspend fun awaitOnce(): Boolean {
        val active = session ?: return false
        if (!receiving.compareAndSet(false, true)) return false
        try {
            _state.value = SourceSyncListenState.Receiving(active.port)
            val outcome = try {
                active.receiveOnce()
            } catch (cancellation: CancellationException) {
                _state.value = listeningStateOf(active)
                throw cancellation
            } catch (unexpected: Exception) {
                _state.value = SourceSyncListenState.Failed(
                    port = active.port,
                    code = RECEIVE_FAILED,
                    detail = unexpected::class.java.simpleName,
                )
                return true
            }
            apply(active, outcome)
            return true
        } finally {
            receiving.set(false)
        }
    }

    /** 关掉监听并释放端口；幂等（已经关了再调什么都不做，第二次不会再 close 一次）。 */
    fun stop() {
        val active = session
        session = null
        if (active != null) runCatching { active.close() }
        _state.value = SourceSyncListenState.Idle
    }

    /** 清掉上一条回执（手选文件 / 重置时用）；监听开着就回到 Listening，不关端口。 */
    fun clearResult() {
        val active = session
        _state.value = if (active == null) SourceSyncListenState.Idle else listeningStateOf(active)
    }

    private fun apply(
        active: SourceSyncListenerSession,
        outcome: SourceSyncTransferImportOutcome,
    ) {
        when (outcome) {
            is SourceSyncTransferImportOutcome.TransferFailed ->
                _state.value = SourceSyncListenState.Failed(active.port, outcome.reason, outcome.detail)

            is SourceSyncTransferImportOutcome.EnvelopeRejected ->
                _state.value = SourceSyncListenState.Rejected(active.port, outcome.reason, outcome.message)

            is SourceSyncTransferImportOutcome.Imported -> {
                val reason = outcome.result.rejectedReason
                if (!reason.isNullOrBlank()) {
                    // 网关整封拒绝（例如信封内重复 syncSourceId）：同样零写入、不碰 session。
                    _state.value = SourceSyncListenState.Rejected(
                        active.port,
                        reason,
                        outcome.result.rejectedMessage,
                    )
                    return
                }
                val envelopeJson = outcome.envelopeJson
                if (envelopeJson.isNullOrBlank()) {
                    _state.value = SourceSyncListenState.Failed(active.port, RECEIVE_FAILED, MISSING_ENVELOPE)
                    return
                }
                onEnvelope(envelopeJson, LISTEN_FILE_NAME)
                _state.value = SourceSyncListenState.Received(
                    port = active.port,
                    deviceId = outcome.result.deviceId,
                    fileName = LISTEN_FILE_NAME,
                    receiptWritten = outcome.receiptWritten,
                )
            }
        }
    }

    private fun listeningStateOf(active: SourceSyncListenerSession) = SourceSyncListenState.Listening(
        port = active.port,
        tls = active.tlsEnabled,
        certificateFingerprint = active.certificateFingerprint,
        host = active.address,
    )

    companion object {

        /** 收到的信封在导入页里的文件名（与手选文件、W6-27 的拨号接收区分）。 */
        const val LISTEN_FILE_NAME: String = "windows-listener.envelope.json"

        /**
         * Android 本地的兜底码：端口不合法 / 监听端口意外抛异常 / Imported 却没带信封原文。
         * 不在线上格式词汇里（线上码全部原样透传，见 docs 第 20 节）。
         */
        const val PORT_INVALID: String = "port-invalid"

        const val RECEIVE_FAILED: String = "receive-failed"

        /** W6-53：开了认证却没填口令（本地拦下，不开 socket）。 */
        const val SECRET_MISSING: String = "listen-secret-missing"

        /** W6-54：等待预算文本不合法（本地拦下，不开 socket）。 */
        const val BUDGET_INVALID: String = "listen-budget-invalid"

        const val MISSING_ENVELOPE: String = "missing-envelope"

        /** 没开监听就想等一次发送时给界面的提示（不是状态行，按钮此时本来就点不动）。 */
        const val NOT_LISTENING: String = "监听没有打开，先点『开始监听』。"
    }
}

/**
 * 生产实现：core:database 的 W6-34 监听服务 + 只接收网关。
 *
 * 网关固定 acceptOnly：监听阶段若接 AndroidSourceSyncService，就等于绕过预览与逐条允许直接写库。
 * 身份证书由调用方注入；今天 App 里还没有证书下发通道，所以默认 [SourceSyncTlsIdentity.NONE]，
 * [tlsAvailability] 会如实返回不可用（见 docs 第 20 节未覆盖项）。
 */
class SourceSyncListenerImportFactory(
    private val identity: SourceSyncTlsIdentity = SourceSyncTlsIdentity.NONE,
    private val gateway: SourceSyncImportGateway = SourceSyncImportGateways.acceptOnly(),
) : SourceSyncListenerFactory {

    override fun tlsAvailability(): SourceSyncListenTlsAvailability =
        if (identity.isUsable) {
            SourceSyncListenTlsAvailability(true, SourceSyncListenCopy.TLS_AVAILABLE_HINT)
        } else {
            SourceSyncListenTlsAvailability(false, SourceSyncListenCopy.TLS_UNAVAILABLE)
        }

    override fun open(port: Int, tls: Boolean): SourceSyncListenerOpenResult = open(port, tls, receipt = false)

    override fun open(port: Int, tls: Boolean, receipt: Boolean): SourceSyncListenerOpenResult =
        open(port, tls, receipt, host = null, allowExplicitHost = false)

    override fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
    ): SourceSyncListenerOpenResult =
        open(port, tls, receipt, host, allowExplicitHost, auth = null)

    override fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
        auth: SourceSyncTransferOptions?,
    ): SourceSyncListenerOpenResult =
        open(
            port,
            tls,
            receipt,
            host,
            allowExplicitHost,
            auth,
            budgetMillis = SourceSyncTransferListener.DEFAULT_ACCEPT_TIMEOUT_MS,
        )

    override fun open(
        port: Int,
        tls: Boolean,
        receipt: Boolean,
        host: String?,
        allowExplicitHost: Boolean,
        auth: SourceSyncTransferOptions?,
        budgetMillis: Int,
    ): SourceSyncListenerOpenResult {
        if (tls && !identity.isUsable) {
            return SourceSyncListenerOpenResult.Failed(
                code = SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
                detail = SourceSyncListenCopy.TLS_UNAVAILABLE,
            )
        }
        val opened = SourceSyncListenerImportService.start(
            gateway = gateway,
            host = host,
            port = port,
            tls = if (tls) SourceSyncTlsOptions.pinned(identity.certificateFingerprint) else SourceSyncTlsOptions.Off,
            identity = identity,
            // W6-54：一个框管两段等待，两段都用同一个毫秒数（理由见 SourceSyncListenCopy.BUDGET_HINT）。
            acceptTimeoutMs = budgetMillis,
            readTimeoutMs = budgetMillis,
            allowExplicitHost = allowExplicitHost,
        )
        return when (opened) {
            is SourceSyncListenerImportStart.Failed ->
                SourceSyncListenerOpenResult.Failed(opened.code, opened.detail)

            is SourceSyncListenerImportStart.Started ->
                SourceSyncListenerOpenResult.Opened(ImportSession(opened.service, receipt, auth))
        }
    }

    private class ImportSession(
        private val service: SourceSyncListenerImportService,
        receipt: Boolean,
        /** W6-53：对端口令要求在开监听时定下来，之后每次 receiveOnce 都用它。 */
        private val auth: SourceSyncTransferOptions?,
    ) : SourceSyncListenerSession {

        /** W6-40：回执开关在开监听时定下来，之后每次 receiveOnce 都用它。 */
        private val receiptOptions: SourceSyncReceiptOptions =
            SourceSyncReceiptOptions.writingOrOff(receipt)

        override val address: String get() = service.address

        override val port: Int get() = service.port

        override val tlsEnabled: Boolean get() = service.tlsEnabled

        override val certificateFingerprint: String? get() = service.certificateFingerprint

        override suspend fun receiveOnce(): SourceSyncTransferImportOutcome =
            service.receiveAndImport(auth = auth, receipt = receiptOptions)

        override fun close() {
            service.close()
        }
    }
}
