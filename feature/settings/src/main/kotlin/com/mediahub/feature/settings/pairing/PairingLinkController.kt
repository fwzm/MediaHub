package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingCodeReasons
import com.mediahub.core.common.pairing.PairingLink
import com.mediahub.core.common.pairing.PairingLinkReasons
import com.mediahub.core.common.pairing.PairingPayload
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 配对链接核对区块的状态（W6-59）。
 *
 * 刻意只暴露「链接本身已经写明」的三件事：设备标识、公钥指纹、剩余有效期，外加对端地址。
 * **挑战（challenge）不在这里**：它是这次邀请的一次性凭据，界面没有理由拿到它，
 * 因此它只留在控制器内部的 [PairingPayload] 里，状态行与 toString 都打不出来。
 */
sealed interface PairingLinkState {

    /** 还没贴过链接。 */
    data object Idle : PairingLinkState

    /** 链接解析成功：三条事实全部来自链接本身，不是从网络拿的。 */
    data class Parsed(
        val deviceId: String,
        val fingerprint: String,
        val endpoint: String,
        val remainingSeconds: Long,
    ) : PairingLinkState

    /** 链接不可用（码来自 core:common 的 PairingLink / PairingCode / PairingPayload）。 */
    data class Failed(val reason: String, val message: String? = null) : PairingLinkState
}

/** 配对链接区块的固定文案（界面、测试与文档共用一份）。 */
object PairingLinkCopy {

    const val TITLE: String = "配对设备"

    const val HINT: String = "把 Windows 显示的配对链接（mediahub://pair?...）贴进来。" +
        "本页只核对链接里写明的设备与公钥指纹，不会连接对端。"

    const val LINK_LABEL: String = "配对链接"

    const val ACTION: String = "核对链接"

    /** 解析成功后的解释：这三条为什么可信。 */
    const val PARSED_HINT: String = "以上三项都由链接本身决定（设备标识、公钥指纹、剩余有效期），" +
        "贴进来之前就已经写在二维码里。"

    /**
     * 为什么本版本核对完就停：把「已实现」与「没做」分开写清楚。
     * Android 侧没有 TLS 1.3 与证书钉扎（Windows 有），所以现在不会去连对端。
     */
    const val NOT_YET: String = "本版本只核对链接，不会连接对端；此入口的安全连接与数据接收尚未开放。"

    const val EMPTY_HINT: String = "链接是空的，先贴上 Windows 显示的那一串。"

    /** 结果行（Idle 没有结果行）。 */
    fun lineOf(state: PairingLinkState): String? = when (state) {
        PairingLinkState.Idle -> null
        is PairingLinkState.Parsed -> "已核对：" + state.deviceId + "，指纹 " + state.fingerprint +
            "，剩余 " + state.remainingSeconds + " 秒（本版本不连接对端）。"
        is PairingLinkState.Failed -> "链接不可用：" + state.reason +
            (state.message?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: "")
    }

    /** 结果行的稳定码（Parsed 为空），供 UI 测试与排障直接断言。 */
    fun codeOf(state: PairingLinkState): String? = when (state) {
        PairingLinkState.Idle -> null
        is PairingLinkState.Parsed -> null
        is PairingLinkState.Failed -> state.reason
    }
}

/**
 * 配对链接核对（W6-59，纯 Kotlin）。
 *
 * 只做一件事：把一段文本按 ADR-S003 的链接契约解析成「设备 / 指纹 / 有效期」，
 * 失败原样透出稳定码。**不拨号、不读文件、不写任何凭据**——网络握手在 Windows 侧有、
 * Android 侧还没有（见 [PairingLinkCopy.NOT_YET]）。
 *
 * 三条不可让步的规矩：
 * 1. **不猜**：只有以 mediahub://pair? 开头的链接才算数，配对码单独贴进来按 not-a-pairing-link 拒；
 * 2. **不泄漏**：挑战只在私有字段里，状态行与 toString 都不含链接原文与挑战；
 * 3. **不留半成品**：解析失败只更新本区块状态，上一次成功的结果与内部载荷一起作废。
 */
class PairingLinkController(
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val _state = MutableStateFlow<PairingLinkState>(PairingLinkState.Idle)

    val state: StateFlow<PairingLinkState> = _state.asStateFlow()

    /** 解析成功时留住的载荷：只为本页后续（W6-59b 的握手）留底，不对外暴露。 */
    private var held: PairingPayload? = null

    /**
     * 核对一段链接。
     *
     * @return true 表示链接可用（状态变成 [PairingLinkState.Parsed]）；
     *   false 表示链接不可用（状态变成 [PairingLinkState.Failed]，内部载荷同时作废）。
     */
    fun verify(link: String): Boolean {
        // 空链接不在本层特判：交给 PairingLink.parse，让它给契约里的 pairing-link-empty，
        // 本层只负责把这条码配上一句人话，不另造 Android 专用码。
        val current = now()
        val result = PairingLink.parse(link, current)
        val payload = result.payload
        if (payload == null) {
            held = null
            val reason = result.reason ?: PairingCodeReasons.PAYLOAD_INVALID
            _state.value = PairingLinkState.Failed(
                reason,
                if (reason == PairingLinkReasons.EMPTY) PairingLinkCopy.EMPTY_HINT else null,
            )
            return false
        }
        held = payload
        _state.value = PairingLinkState.Parsed(
            deviceId = payload.deviceId,
            fingerprint = payload.publicKeyFingerprint.displayString,
            endpoint = endpointOf(payload.ephemeralUrl),
            remainingSeconds = payload.remainingMilliseconds(current) / MILLIS_PER_SECOND,
        )
        return true
    }

    /** 换页或重新贴链接时清掉上一次结论。 */
    fun clear() {
        held = null
        _state.value = PairingLinkState.Idle
    }

    private fun endpointOf(url: String): String {
        val trimmed = url.trim()
        return try {
            val uri = URI(trimmed)
            val host = uri.host ?: return trimmed
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme?.equals("https", ignoreCase = true) == true -> DEFAULT_HTTPS_PORT
                else -> DEFAULT_HTTP_PORT
            }
            host + ":" + port
        } catch (unexpected: Exception) {
            // 载荷校验已经保证是绝对 http/https URL；这里只是不让一个意外的串把界面搞崩。
            trimmed
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND: Long = 1_000L
        const val DEFAULT_HTTPS_PORT: Int = 443
        const val DEFAULT_HTTP_PORT: Int = 80
    }
}
