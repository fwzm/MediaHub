package com.mediahub.feature.settings.sync

import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.sync.SourceSyncDeliveryOptions
import com.mediahub.core.common.sync.SourceSyncReceiptOptions
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTransferClient
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.common.sync.SourceSyncTransferResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 发送区块（W6-42：Android 也当发送端，把本机导出的信封推给 Windows 的 transfer serve）的状态。
 *
 * 与接收 / 监听两区块同一形状：纯 Kotlin、不碰 Compose，四种状态各自只由一次发送的结果决定；
 * 失败一律带稳定码，[Failed.code] 原样来自底层（not-loopback / connect-failed / io-failed /
 * tls-* / auth-* / receipt-*），本层自己只补少数「还没拨号就拒」的码（见 [SourceSyncSendController]）。
 */
sealed interface SourceSyncSendState {

    /** 还没发过，或者刚被清掉。 */
    data object Idle : SourceSyncSendState

    /** 正在导出并发送（界面据此禁用按钮 + 显示进度）。 */
    data object Sending : SourceSyncSendState

    /**
     * 帧已经全部写进 socket。
     *
     * [receipt] 为 true 才表示「对端回了 0x04」——只有这种情形下「发送成功」才等于「对端收下了」；
     * 关着等回执时这个状态只说明字节写完了（对端拒收之后同样会关连接，见 Windows W6-35 case R6a）。
     *
     * [deliveryConfirmed] 是 W6-46 的投递确认：true 表示写完帧后读到对端关了连接；
     * null 表示没开确认，或者被回执取代（回执优先，两个都开时只有回执说话）；
     * 开了确认却读不到 FIN 时不会落到这个状态，而是 [Failed] + delivery-unconfirmed。
     */
    data class Sent(
        val bytes: Int,
        val frames: Int,
        val receipt: Boolean,
        val sourceCount: Int = 0,
        val skippedCount: Int = 0,
        val deliveryConfirmed: Boolean? = null,
    ) : SourceSyncSendState

    /** 失败：稳定码 + 底层诊断原文（不折成布尔，也不新造同义码）。 */
    data class Failed(val code: String, val detail: String? = null) : SourceSyncSendState
}

/** 发送区块的固定文案与「状态 → 一行字」映射（与接收 / 监听同风格，纯函数便于单测）。 */
object SourceSyncSendCopy {

    const val TITLE = "发送（Android → Windows）"

    const val HINT = "把本机来源导出成一封信封，推给 Windows 上正在跑的 transfer serve。" +
        "只发送，不改本机数据；是否导入由 Windows 侧决定。"

    const val HOST_LABEL = "Windows 地址（只允许回环）"

    const val PORT_LABEL = "端口（必填，1-65535）"

    const val CHALLENGE_LABEL = "挑战 / nonce（可选；开认证时必填，两端要一致）"

    const val TLS_LABEL = "TLS 1.3 + 指纹钉扎"

    const val PIN_LABEL = "期望指纹（64 位 hex，Windows serve 的 stderr 里有）"

    const val AUTH_LABEL = "HMAC 认证（口令只留在内存）"

    const val SECRET_LABEL = "口令"

    const val RECEIPT_LABEL = "等回执（0x04）"

    const val DELIVERY_LABEL = "等对端关闭连接（投递确认）"

    const val ACTION = "发送"

    /** W6-49：发送中可取消；取消不是失败，界面不许因此显示失败码。 */
    const val CANCEL = "取消发送"

    const val BUSY = "发送中…"

    const val PORT_REQUIRED = "端口必须是 1-65535 的数字，填好才能发送（发送端没有系统分配端口）。"

    const val PIN_REQUIRED = "开了 TLS 就必须填 64 位 hex 指纹：钉扎不做「信任首次」，没有指纹就不拨号。"

    const val SECRET_REQUIRED = "开了认证就必须填口令：没有口令的 tag 帧对端会判 auth-secret-unavailable。"

    const val RECEIPT_HINT = "开了等回执，对端回了 0x04 才算送达；关着时「成功」只代表字节写完了。"

    const val DELIVERY_HINT = "开了投递确认，读完信封后对端关连接才算送达；与回执同时开时回执优先。"

    /** 状态 → 一行中文（Idle 时没有可显示的行）。 */
    fun lineOf(state: SourceSyncSendState): String? = when (state) {
        SourceSyncSendState.Idle -> null
        SourceSyncSendState.Sending -> BUSY
        is SourceSyncSendState.Sent -> sentText(state)
        is SourceSyncSendState.Failed -> "发送失败：" + state.code + detailSuffix(state.detail)
    }

    /** 失败时的稳定码（成功与空闲没有码）。 */
    fun codeOf(state: SourceSyncSendState): String? = when (state) {
        SourceSyncSendState.Idle, SourceSyncSendState.Sending -> null
        is SourceSyncSendState.Sent -> null
        is SourceSyncSendState.Failed -> state.code
    }

    private fun sentText(state: SourceSyncSendState.Sent): String {
        val head = "已发送 " + state.bytes + " 字节（" + state.frames + " 帧，来源 " + state.sourceCount + " 条）。"
        val skipped = if (state.skippedCount > 0) {
            "跳过 " + state.skippedCount + " 条（没有线路或类型未知，Windows 侧会看到它们缺席）。"
        } else {
            ""
        }
        val delivery = if (state.receipt) {
            "对端回了 0x04 回执，可以确认它收下了这封信封。"
        } else {
            "没有等回执：这只说明字节写完了，不代表对端接受了。"
        }
        val closed = if (state.deliveryConfirmed == true) {
            "对端读完就关了连接，这封信封确实送到了。"
        } else {
            ""
        }
        return head + skipped + delivery + closed
    }

    private fun detailSuffix(detail: String?): String {
        val trimmed = detail?.trim().orEmpty()
        return if (trimmed.isEmpty()) "" else "（" + trimmed + "）"
    }
}

/**
 * 一次导出能交给发送端的东西。
 *
 * 刻意只带「JSON + 两个计数」，不把 core:database 的导出类型泄漏到本层：
 * 这样控制器可以在 JVM 单测里用假端口跑，也方便测试直接钉住线上字节。
 */
data class SourceSyncSendExport(
    val envelopeJson: String,
    val sourceCount: Int = 0,
    val skippedCount: Int = 0,
)

/** 导出端口：生产用 AndroidSourceSyncService.export()（core:database），测试用假实现。 */
fun interface SourceSyncExportPort {
    suspend fun export(): SourceSyncSendExport
}

/**
 * 传输端口：一次调用 = 一次连接（拨号、写帧、按需等回执、关闭）。
 *
 * 默认实现 [SourceSyncTransferSendPort] 直接转调 core:common 的 SourceSyncTransferClient，
 * 协议逻辑一行都没有复制到这里（分帧、认证 tag、TLS 握手、回执都在 core:common 里）。
 */
fun interface SourceSyncSendPort {
    suspend fun send(
        host: String,
        port: Int,
        envelope: ByteArray,
        challenge: String?,
        tls: SourceSyncTlsOptions,
        auth: SourceSyncTransferOptions?,
        receipt: SourceSyncReceiptOptions,
        delivery: SourceSyncDeliveryOptions,
    ): SourceSyncTransferResult
}

/** 生产传输端口：阻塞的 socket 调用落在控制器给的 Dispatchers.IO 上，这里只做转调。 */
class SourceSyncTransferSendPort(
    private val client: SourceSyncTransferClient = SourceSyncTransferClient(),
) : SourceSyncSendPort {

    override suspend fun send(
        host: String,
        port: Int,
        envelope: ByteArray,
        challenge: String?,
        tls: SourceSyncTlsOptions,
        auth: SourceSyncTransferOptions?,
        receipt: SourceSyncReceiptOptions,
        delivery: SourceSyncDeliveryOptions,
    ): SourceSyncTransferResult = client.sendTo(
        host = host,
        port = port,
        envelope = envelope,
        challenge = challenge,
        tls = tls,
        auth = auth,
        receipt = receipt,
        delivery = delivery,
    )
}

/**
 * 发送表单（W6-42）。
 *
 * 默认值刻意安全：只连 127.0.0.1、端口空（必须自己填）、TLS / 认证 / 回执全关；
 * 关着时线上字节与 W6-22 / W6-25 冻结向量逐字节等价（协议那一层由 core:common 保证）。
 *
 * 口令只在内存里：[secret] 不会出现在 [toString] 里，也不会被写进状态或异常文本
 * （控制器只把异常的类名写进 detail）。
 */
data class SourceSyncSendForm(
    val host: String = DEFAULT_HOST,
    val port: String = "",
    val challenge: String = "",
    val tls: Boolean = false,
    val fingerprint: String = "",
    val auth: Boolean = false,
    val secret: String = "",
    val receipt: Boolean = false,
    val delivery: Boolean = false,
) {

    /** 空白地址回落到回环；不做 DNS（底层 LoopbackOnly 只认字面量）。 */
    val hostOrLoopback: String
        get() = host.trim().ifEmpty { DEFAULT_HOST }

    /** 端口：发送端没有「系统分配」，所以 0 与越界一样非法。 */
    val portNumber: Int?
        get() = port.trim().toIntOrNull()?.takeIf { it in VALID_PORTS }

    val challengeOrNull: String?
        get() = challenge.trim().takeIf { it.isNotEmpty() }

    /** 指纹解析结果（严格 64 hex；解析失败为 null）。 */
    val fingerprintOrNull: PairingFingerprint?
        get() = PairingFingerprint.tryParse(fingerprint.trim())

    val isFingerprintAcceptable: Boolean
        get() = !tls || fingerprintOrNull != null

    val isSecretAcceptable: Boolean
        get() = !auth || secret.isNotEmpty()

    val canSubmit: Boolean
        get() = portNumber != null && isFingerprintAcceptable && isSecretAcceptable

    /** 诊断 / 日志安全：口令原文一律不出现在 toString 里。 */
    override fun toString(): String = "SourceSyncSendForm(host=" + hostOrLoopback +
        ", port=" + port +
        ", challenge=" + (if (challengeOrNull == null) "<none>" else "<set>") +
        ", tls=" + tls +
        ", fingerprint=" + (if (fingerprintOrNull == null) "<none>" else "<set>") +
        ", auth=" + auth +
        ", secret=<redacted>" +
        ", receipt=" + receipt +
        ", delivery=" + delivery + ")"

    companion object {

        /** 默认只连本机回环。 */
        const val DEFAULT_HOST: String = "127.0.0.1"

        /** 发送端可用的端口区间（0 留给监听端的「系统分配」）。 */
        val VALID_PORTS: IntRange = 1..65_535
    }
}

/**
 * 发送控制器（W6-42）：导出本机信封 → 通过回环 socket 推给 Windows transfer serve。
 *
 * 纪律（与 W6-27 / W6-36 同一套）：
 * - 本层不复制协议：分帧、HMAC tag、TLS 握手、回执四码全部来自 core:common，码原样透传；
 * - 本层只补「还没拨号就拒」的码：send-port-invalid / send-fingerprint-invalid /
 *   send-secret-missing / send-export-failed / send-failed；
 * - 零写入：发送只读本机导出，不改任何本地数据（导出本身会为新来源补号，见
 *   AndroidSourceSyncService.export 的 KDoc）；
 * - 单飞：AtomicBoolean 保证重复点击只发一次；取消回到 Idle，不把半截连接留成状态；
 * - 口令不出门：只在构造 [SourceSyncTransferOptions] 时用一次，状态里只有稳定码与诊断。
 */
class SourceSyncSendController(
    private val exportPort: SourceSyncExportPort,
    private val sendPort: SourceSyncSendPort,
) {

    private val _state = MutableStateFlow<SourceSyncSendState>(SourceSyncSendState.Idle)

    /** 界面订阅的四态。 */
    val state: StateFlow<SourceSyncSendState> = _state.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    /** 是否有一条发送在飞（界面用来禁用按钮，测试用来断言单飞）。 */
    val isSending: Boolean
        get() = inFlight.get()

    /**
     * 发一次：导出一封信封并按表单推给对端。
     *
     * 返回 false 表示什么都没做（端口非法 / 指纹解析失败 / 口令缺失 / 已有一条在飞）；
     * 返回 true 表示状态已经落成 Sent 或 Failed。
     */
    suspend fun send(form: SourceSyncSendForm): Boolean {
        val port = form.portNumber
        if (port == null) {
            _state.value = SourceSyncSendState.Failed(PORT_INVALID, SourceSyncSendCopy.PORT_REQUIRED)
            return false
        }
        if (!form.isFingerprintAcceptable) {
            _state.value = SourceSyncSendState.Failed(FINGERPRINT_INVALID, SourceSyncSendCopy.PIN_REQUIRED)
            return false
        }
        if (!form.isSecretAcceptable) {
            _state.value = SourceSyncSendState.Failed(SECRET_MISSING, SourceSyncSendCopy.SECRET_REQUIRED)
            return false
        }
        if (!inFlight.compareAndSet(false, true)) {
            return false
        }
        _state.value = SourceSyncSendState.Sending
        try {
            // 导出阶段单独兜一层：这一步失败时一次连接都没有建立，与「写到一半失败」区分开
            // （EXPORT_FAILED vs SEND_FAILED），排障时先看是不是本机仓库读不出来。
            val exported = try {
                withContext(Dispatchers.IO) { exportPort.export() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = SourceSyncSendState.Failed(EXPORT_FAILED, error.javaClass.simpleName)
                return true
            }
            val payload = exported.envelopeJson.toByteArray(Charsets.UTF_8)
            val tls = if (form.tls) {
                SourceSyncTlsOptions.Pinned(form.fingerprintOrNull?.hex.orEmpty())
            } else {
                SourceSyncTlsOptions.Off
            }
            val auth = if (form.auth) {
                SourceSyncTransferOptions.withSecret(form.secret.toByteArray(Charsets.UTF_8))
            } else {
                null
            }
            val receipt = if (form.receipt) {
                SourceSyncReceiptOptions.awaiting()
            } else {
                SourceSyncReceiptOptions.Off
            }
            // 回执优先：两个都开时确认被忽略（core:common 的 sendTo 里也再兜一次，避免调用方绕过）。
            val delivery = SourceSyncDeliveryOptions.confirmingOrOff(form.delivery && !form.receipt)
            val result = withContext(Dispatchers.IO) {
                sendPort.send(
                    host = form.hostOrLoopback,
                    port = port,
                    envelope = payload,
                    challenge = form.challengeOrNull,
                    tls = tls,
                    auth = auth,
                    receipt = receipt,
                    delivery = delivery,
                )
            }
            _state.value = when (result) {
                is SourceSyncTransferResult.Ok -> SourceSyncSendState.Sent(
                    bytes = payload.size,
                    frames = result.frameCount,
                    receipt = form.receipt,
                    sourceCount = exported.sourceCount,
                    skippedCount = exported.skippedCount,
                    // 开了确认又能走到 Ok，说明确实读到了 FIN；被回执取代或没开时一律 null。
                    deliveryConfirmed = if (delivery.confirm) true else null,
                )
                is SourceSyncTransferResult.Failed -> SourceSyncSendState.Failed(result.reason, result.message)
            }
            return true
        } catch (cancelled: CancellationException) {
            _state.value = SourceSyncSendState.Idle
            throw cancelled
        } catch (error: Exception) {
            // 只写类名：异常文本可能带地址、口令片段或对端输入，绝不能进状态。
            _state.value = SourceSyncSendState.Failed(SEND_FAILED, error.javaClass.simpleName)
            return true
        } finally {
            inFlight.set(false)
        }
    }

    /** 清掉上一次的结论（回到 Idle）；不影响任何进行中的发送。 */
    fun clearResult() {
        _state.value = SourceSyncSendState.Idle
    }

    companion object {

        /** 端口非法（0 / 越界 / 非数字）：还没导出、也没拨号。 */
        const val PORT_INVALID: String = "send-port-invalid"

        /** TLS 开着但指纹不是 64 hex：还没导出、也没拨号。 */
        const val FINGERPRINT_INVALID: String = "send-fingerprint-invalid"

        /** 认证开着但口令为空：还没导出、也没拨号。 */
        const val SECRET_MISSING: String = "send-secret-missing"

        /** 导出失败（本机仓库读不出来）：一次连接都没有建立。 */
        const val EXPORT_FAILED: String = "send-export-failed"

        /** 未预期异常（只带类名）：与其他码区分开，排障时先看是不是本层崩了。 */
        const val SEND_FAILED: String = "send-failed"
    }
}
