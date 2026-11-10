package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairedDeviceRegistry
import com.mediahub.core.common.pairing.PairedFingerprintState
import com.mediahub.core.common.pairing.PairingAddressEnumerator
import com.mediahub.core.common.pairing.PairingAddressReport
import com.mediahub.core.common.pairing.PairingCardExchangeResult
import com.mediahub.core.common.pairing.PairingCodeReasons
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingLink
import com.mediahub.core.common.pairing.PairingLinkReasons
import com.mediahub.core.common.pairing.PairingPayload
import com.mediahub.core.common.sync.WireText
import com.mediahub.core.common.pairing.PairingPeerCard
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 配对页的状态（W6-59 核对链接 + W6-59b 拉邀请）。
 *
 * 对外只暴露链接与对端回复里已经写明的三件事：设备标识、公钥指纹、剩余有效期，外加对端地址。
 * **挑战（challenge）不在这里**：它是这次邀请的一次性凭据，界面没有理由拿到它，
 * 因此它只留在控制器内部的 [PairingPayload] 里，状态行与 toString 都打不出来。
 */
sealed interface PairingLinkState {

    /** 还没贴过链接。 */
    data object Idle : PairingLinkState

    /** 链接解析成功但还没连对端：三条事实全部来自链接本身。 */
    data class Parsed(
        val deviceId: String,
        val fingerprint: String,
        val endpoint: String,
        val remainingSeconds: Long,
    ) : PairingLinkState

    /** 正在按链接里的地址与指纹去拉邀请（TLS 1.3 + SPKI 钉扎）。 */
    data class Fetching(
        val deviceId: String,
        val fingerprint: String,
        val endpoint: String,
    ) : PairingLinkState

    /** 对端回的邀请已通过全部校验：帧、载荷、时效、挑战、指纹，一个都没少。 */
    data class Fetched(
        val deviceId: String,
        val fingerprint: String,
        val endpoint: String,
        val remainingSeconds: Long,
    ) : PairingLinkState

    /** 失败（码来自 core:common 的 Pairing 词汇表，或本层的前置条件码）。 */
    data class Failed(val reason: String, val message: String? = null) : PairingLinkState
}

/**
 * 「本机可被连的地址」区块的状态（W6-60）。
 *
 * 与 [PairingLinkState] 分开：这一块和「贴链接」是两条互不相干的信息，
 * 混成一个状态会让「链接还没贴」和「网卡还没读」互相压制。
 */
sealed interface PairingAddressState {

    /** 还没读过网卡。 */
    data object NotRead : PairingAddressState

    /** 读到了（可能没有可用地址，此时 report.reason 说明是哪一种）。 */
    data class Ready(val report: PairingAddressReport) : PairingAddressState

    /** 本次构建没有可用的枚举出口（界面预览态或未接线）：不读、不假装读到空。 */
    data object Unavailable : PairingAddressState
}

/**
 * 对端身份核对与登记的状态（W6-71）。
 *
 * 与 [PairingLinkState] 分开：邀请校验管的是「对端确实是链接里那一台」，
 * 这里管的是「拉完之后对端卡片上的稳定指纹与本机登记簿比得怎么样、要不要记住它」。
 * 两步分开的理由与 Windows 一致：卡片可能根本拿不到（老构建只发邀请），
 * 拿不到也不该把已经校验通过的邀请变红。
 */
sealed interface PairingPeerState {

    /** 还没拉过邀请，没有可核对的东西。 */
    data object NotChecked : PairingPeerState

    /** 邀请校验通过了，但对端没有回身份卡片：配对成立，只是这次记不住对端。 */
    data object NoCard : PairingPeerState

    /** 本次构建没有可用的登记簿（预览态或未接线），没法比对指纹。 */
    data object Unavailable : PairingPeerState

    /** 拿到对端卡片并已与登记簿比对过：只读结论，**不会自动写盘**。 */
    data class Reviewed(
        val deviceId: String,
        val displayName: String,
        val fingerprint: String,
        val state: PairedFingerprintState,
        val reason: String,
        val pinnedFingerprint: String?,
        val canRegister: Boolean,
    ) : PairingPeerState

    /** 点过「登记这台设备」之后的回执：accepted=false 表示登记被拒（指纹不一致或存储不可用）。 */
    data class Recorded(
        val deviceId: String,
        val displayName: String,
        val fingerprint: String,
        val state: PairedFingerprintState,
        val reason: String,
        val accepted: Boolean,
        val added: Boolean,
    ) : PairingPeerState
}

/** 对端身份区块的固定文案（界面与测试共用一份）。 */
object PairingPeerCopy {

    const val TITLE: String = "对端身份"

    const val HINT: String = "拉邀请成功后，对端会回一张身份卡片。这张卡片里的指纹是**稳定指纹**，" +
        "和邀请里那枚只有一次性的证书指纹不是一回事：只有卡片指纹才能登记，否则下次配对必然误判。"

    const val NOT_CHECKED_HINT: String = "还没拉到邀请，没有可核对的对端身份。"

    const val NO_CARD_HINT: String = "对端没有回身份卡片（可能是没有登记簿的老构建）：这次配对成立，只是记不住对端。"

    const val UNAVAILABLE_HINT: String = "本机没有可用的已配对设备登记，无法比对指纹。"

    const val REGISTER_ACTION: String = "登记这台设备"

    const val REGISTER_HINT: String = "登记只写本机的配对登记簿：不写任何凭据、不改对端，也不建立同步。"

    const val MISMATCH_NOTE: String = "本机不会覆盖已登记的指纹，请当面核对。"

    /** 结果行（NotChecked 没有结果行）。 */
    fun lineOf(state: PairingPeerState): String? = when (state) {
        PairingPeerState.NotChecked -> null
        PairingPeerState.NoCard -> NO_CARD_HINT
        PairingPeerState.Unavailable -> UNAVAILABLE_HINT
        is PairingPeerState.Reviewed -> {
            val head = "对端：" + state.displayName + "（" + state.deviceId + "），指纹 " + state.fingerprint +
                "；" + PairedDeviceRegistry.describeState(state.state) + "。"
            val detail = PairedDeviceRegistry.describeReason(state.reason)
            if (state.state == PairedFingerprintState.MISMATCH) {
                head + detail + MISMATCH_NOTE
            } else {
                head + detail
            }
        }
        is PairingPeerState.Recorded -> {
            val who = state.displayName + "（" + state.deviceId + "）"
            if (state.accepted) {
                if (state.added) "已登记 " + who + "：首次配对，指纹已钉住。"
                else "已登记 " + who + "：本机记录已更新。"
            } else {
                "没有登记 " + who + "：" + PairedDeviceRegistry.describeReason(state.reason)
            }
        }
    }

    /** 结果行的稳定码（无结果或成功态为空），供 UI 测试与排障直接断言。 */
    fun codeOf(state: PairingPeerState): String? = when (state) {
        PairingPeerState.NotChecked -> null
        PairingPeerState.NoCard -> null
        PairingPeerState.Unavailable -> PairingLinkController.REGISTRY_UNAVAILABLE
        is PairingPeerState.Reviewed -> state.reason
        is PairingPeerState.Recorded -> state.reason
    }
}

/** 配对页的固定文案（界面、测试与文档共用一份）。 */
object PairingLinkCopy {

    const val TITLE: String = "配对设备"

    const val HINT: String = "把 Windows 显示的配对链接（mediahub://pair?…）贴进来，先在本地核对它写了什么，" +
        "再按链接里的地址与公钥指纹去拉一次邀请。"

    const val LINK_LABEL: String = "配对链接"

    const val ACTION: String = "核对链接"

    const val FETCH_ACTION: String = "拉取邀请"

    /** 解析成功后的解释：这三条为什么可信。 */
    const val PARSED_HINT: String = "以上三项都由链接本身决定（设备标识、公钥指纹、剩余有效期），" +
        "贴进来之前就已经写在二维码里。"

    /** 拉邀请这一步到底做了什么、凭什么信对端。 */
    const val FETCH_HINT: String = "拉取邀请会用链接里写着的那枚公钥指纹去钉对端的 TLS 1.3 证书：" +
        "指纹对不上就判 fingerprint-mismatch，对端回传的挑战与本端不一致就判 challenge-mismatch，过期的邀请判 lifetime-exceeded。" +
        "本页不写任何凭据，也不把挑战显示出来。"

    /** 拉成功后的解释：邀请校验过，但还没建立同步。 */
    const val FETCHED_HINT: String = "对端回的邀请已经逐项校验通过。这一步只证明「对端确实是链接里那一台」，" +
        "同步仍要在来源同步页里单独发起。"

    /** 没先核对链接就点拉邀请。 */
    const val NOT_VERIFIED_HINT: String = "先核对链接，再去拉邀请：不知道对端是谁、指纹是多少，就没有可钉的对象。"

    const val EMPTY_HINT: String = "链接是空的，先贴上 Windows 显示的那一串。"

    const val FETCHING_HINT: String = "正在拉邀请（TLS 1.3 + 指纹钉扎），请稍候。"

    /** 本页的边界：只核对与拉邀请，不写凭据、不建立同步。 */
    const val NOT_YET: String = "本页只做「核对链接 + 拉一次邀请」：不写任何凭据、不建立同步，" +
        "来源同步仍要在来源同步页里单独发起。"

    /** 本次构建没有可用的拉取出口（界面预览态或未接线）。 */
    const val FETCH_UNAVAILABLE_HINT: String = "这个页面当前没有可用的拉取出口（预览态或未接线），没有拨号。"

    /** 结果行（Idle 没有结果行）。 */
    fun lineOf(state: PairingLinkState): String? = when (state) {
        PairingLinkState.Idle -> null
        is PairingLinkState.Parsed -> "已核对：" + state.deviceId + "，指纹 " + state.fingerprint +
            "，剩余 " + state.remainingSeconds + " 秒（还没连对端）。"
        is PairingLinkState.Fetching -> "正在连接 " + state.endpoint + "，等它回邀请…"
        is PairingLinkState.Fetched -> "邀请已校验：" + state.deviceId + "，指纹 " + state.fingerprint +
            "，剩余 " + state.remainingSeconds + " 秒。"
        is PairingLinkState.Failed -> "配对不可用：" + state.reason +
            (state.message?.takeIf { it.isNotBlank() }?.let { "（" + it + "）" } ?: "")
    }

    /** 结果行的稳定码（成功态为空），供 UI 测试与排障直接断言。 */
    fun codeOf(state: PairingLinkState): String? = when (state) {
        PairingLinkState.Idle -> null
        is PairingLinkState.Parsed -> null
        is PairingLinkState.Fetching -> null
        is PairingLinkState.Fetched -> null
        is PairingLinkState.Failed -> state.reason
    }
}

/**
 * 配对入口的控制器（W6-59 核对链接 + W6-59b 拉邀请，纯 Kotlin）。
 *
 * 两步刻意分开：
 * 1. [verify] 只解析链接，一个字节都不发——先让用户看清「这是哪台机器、指纹是多少、还剩多久」；
 * 2. [fetchInvitation] 才按链接里的地址与**那一枚**指纹去拨号，TLS 1.3 握手时钉扎它，
 *    再把对端回的邀请逐项校验（帧 / 载荷 / 时效 / 挑战 / 指纹）。
 *
 * 不写任何凭据、不落盘、不泄漏挑战：挑战只在私有字段里，界面拿不到。
 */
class PairingLinkController(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val fetchPort: PairingFetchPort? = null,
    private val addressEnumerator: PairingAddressEnumerator? = null,
    /** 本机的已配对设备登记簿（W6-71）；为 null 表示本次构建没有登记能力，只核对不登记。 */
    private val registry: PairedDeviceRegistry? = null,
    /** 本机身份卡片；只有 [registry] 存在时才会随拉邀请发给对端（与 Windows 同一条边界）。 */
    private val selfCard: PairingPeerCard? = null,
) {

    private val _state = MutableStateFlow<PairingLinkState>(PairingLinkState.Idle)

    val state: StateFlow<PairingLinkState> = _state.asStateFlow()

    private val _addresses = MutableStateFlow<PairingAddressState>(
        if (addressEnumerator == null) PairingAddressState.Unavailable else PairingAddressState.NotRead,
    )

    /** 本机可被连的地址（只读展示，见 [refreshAddresses]）。 */
    val addresses: StateFlow<PairingAddressState> = _addresses.asStateFlow()

    private val _peer = MutableStateFlow<PairingPeerState>(PairingPeerState.NotChecked)

    /** 对端身份核对与登记的状态（W6-71）。 */
    val peer: StateFlow<PairingPeerState> = _peer.asStateFlow()


    private val readingAddresses = AtomicBoolean(false)

    /** 解析成功时留住的载荷：挑战与指纹都在这里，界面看不到。 */
    private var held: PairingPayload? = null

    private val fetching = AtomicBoolean(false)

    /** 上一次核对过的链接里写明的对端地址与端口，供 [fetchInvitation] 用。 */
    private var heldEndpoint: Endpoint? = null

    /** 最近一次拉到的对端身份卡片；只有它才能被登记（W6-71）。 */
    private var heldPeerCard: PairingPeerCard? = null

    /**
     * 核对一段链接：只解析，不发任何字节。
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
            clearHeld()
            val reason = result.reason ?: PairingCodeReasons.PAYLOAD_INVALID
            _state.value = PairingLinkState.Failed(
                reason,
                if (reason == PairingLinkReasons.EMPTY) PairingLinkCopy.EMPTY_HINT else null,
            )
            return false
        }
        held = payload
        heldEndpoint = endpointOf(payload.ephemeralUrl)

        // 换链接就作废上一次的对端核对结论（W6-71）。
        heldPeerCard = null
        _peer.value = PairingPeerState.NotChecked
        _state.value = PairingLinkState.Parsed(
            deviceId = payload.deviceId,
            fingerprint = payload.publicKeyFingerprint.displayString,
            endpoint = endpointText(payload.ephemeralUrl),
            remainingSeconds = payload.remainingMilliseconds(current) / MILLIS_PER_SECOND,
        )
        return true
    }

    /**
     * 按上一次核对过的链接去拉邀请（W6-59b：真拨号 + TLS 1.3 + 指纹钉扎）。
     *
     * 没有先核对过链接、没有配 fetch 出口、或者已经有一次在飞，都直接返回 false 并给出稳定码，
     * 不拨号。失败码一律来自 core:common 的配对词汇表（或本层的前置条件码）。
     */
    suspend fun fetchInvitation(): Boolean {
        val payload = held
        val endpoint = heldEndpoint
        val port = fetchPort
        if (payload == null || endpoint == null) {
            _state.value = PairingLinkState.Failed(PAIRING_NOT_VERIFIED, PairingLinkCopy.NOT_VERIFIED_HINT)
            return false
        }
        if (port == null) {
            _state.value = PairingLinkState.Failed(
                PAIRING_FETCH_UNAVAILABLE,
                PairingLinkCopy.FETCH_UNAVAILABLE_HINT,
            )
            return false
        }
        if (!fetching.compareAndSet(false, true)) {
            return false
        }
        val deviceId = payload.deviceId
        val fingerprint = payload.publicKeyFingerprint
        _state.value = PairingLinkState.Fetching(
            deviceId = deviceId,
            fingerprint = fingerprint.displayString,
            endpoint = endpointText(payload.ephemeralUrl),
        )
        // 只有有登记簿的一方才自报身份：没有登记簿时发卡片没有意义，也少一个对端可被跟踪的面。
        val card = if (registry == null) null else selfCard
        try {
            val outcome = port.fetchWithCard(
                host = endpoint.host,
                port = endpoint.port,
                expectedChallenge = payload.challenge,
                expectedFingerprint = fingerprint,
                selfCard = card,
            )
            return when (outcome) {
                is PairingCardExchangeResult.Ok -> {
                    val current = now()
                    _state.value = PairingLinkState.Fetched(
                        deviceId = outcome.payload.deviceId,
                        fingerprint = outcome.payload.publicKeyFingerprint.displayString,
                        endpoint = endpointText(outcome.payload.ephemeralUrl),
                        remainingSeconds = outcome.payload.remainingMilliseconds(current) / MILLIS_PER_SECOND,
                    )
                    // 邀请已经校验通过；卡片这一步只决定「记不记得住对端」，不影响上面的结论。
                    heldPeerCard = outcome.peerCard
                    _peer.value = reviewPeerCard(outcome.peerCard)
                    true
                }
                is PairingCardExchangeResult.Failed -> {
                    _state.value = PairingLinkState.Failed(outcome.reason, outcome.message)
                    heldPeerCard = null
                    _peer.value = PairingPeerState.NotChecked
                    false
                }
            }
        } finally {
            fetching.set(false)
        }
    }

    /**
     * 把对端卡片与登记簿比一次（只读，不写盘）。
     *
     * 与 Windows `SyncPairingPresenter.VerifyReply` 同义：指纹不一致时绝不自动登记；
     * 拿不到卡片、或本机没有登记簿，也都是「这次记不住对端」，不是错误。
     */
    private fun reviewPeerCard(card: PairingPeerCard?): PairingPeerState {
        val store = registry ?: return PairingPeerState.Unavailable
        if (card == null) {
            return PairingPeerState.NoCard
        }
        val verification = store.verify(card.deviceId, card.deviceFingerprint)
        return PairingPeerState.Reviewed(
            deviceId = card.deviceId,
            displayName = nameOf(card),
            fingerprint = verification.presentedFingerprint,
            state = verification.state,
            reason = verification.reason,
            pinnedFingerprint = verification.pinnedFingerprint,
            canRegister = verification.canPin,
        )
    }

    /**
     * 显式把对端卡片登记进本机登记簿（W6-71 的登记动作）。
     *
     * 三条前置条件不满足就直接返回 false 并把稳定码写进 [peer]：
     * 没有登记簿（unavailable）、没有卡片（no-card）、指纹与已登记的**不一致**（mismatch）。
     * 指纹不一致时一个字节都不写：覆盖已钉住的指纹等于默默接受冒充者（与 Windows 同一条注释）。
     *
     * @return true 表示登记被接受（新增或更新）。
     */
    fun registerPeer(): Boolean {
        val store = registry
        if (store == null) {
            _peer.value = PairingPeerState.Unavailable
            return false
        }
        val card = heldPeerCard
        if (card == null) {
            _peer.value = PairingPeerState.NoCard
            return false
        }
        val verification = store.verify(card.deviceId, card.deviceFingerprint)
        if (verification.isMismatch) {
            // 绝不覆盖：只把回执落在界面上，登记簿保持不变。
            _peer.value = PairingPeerState.Recorded(
                deviceId = card.deviceId,
                displayName = nameOf(card),
                fingerprint = verification.presentedFingerprint,
                state = verification.state,
                reason = verification.reason,
                accepted = false,
                added = false,
            )
            return false
        }
        if (!verification.canPin) {
            _peer.value = PairingPeerState.Recorded(
                deviceId = card.deviceId,
                displayName = nameOf(card),
                fingerprint = verification.presentedFingerprint,
                state = verification.state,
                reason = verification.reason,
                accepted = false,
                added = false,
            )
            return false
        }
        val registration = store.register(card.toObservation(now()))
        _peer.value = PairingPeerState.Recorded(
            deviceId = card.deviceId,
            displayName = nameOf(card),
            fingerprint = card.deviceFingerprint,
            state = registration.state,
            reason = registration.reason,
            accepted = registration.accepted,
            added = registration.added,
        )
        return registration.accepted
    }

    /** 卡片的展示名：没有名字就退回设备标识（与 PairedDeviceListPresenter 的兜底同义）。 */
    private fun nameOf(card: PairingPeerCard): String =
        if (WireText.isBlank(card.displayName)) card.deviceId else card.displayName
    /**
     * 读一次本机网卡，得到「可被连的地址」（W6-60）。
     *
     * 同步读：只查本机网卡属性（不发包、不解析 DNS、不 bind），与 Windows 的 Describe() 同形状，
     * 因此不需要协程，JVM 单测可以直接调。已经有一次在飞就直接返回 false，不读第二遍。
     *
     * 读不到一律落成 address-enumeration-failed（由 PairingAddressCandidates.failed 给），
     * 不新造 Android 专用码；没有可用地址且读成功则是 no-usable-address。
     */
    fun refreshAddresses(): Boolean {
        val enumerator = addressEnumerator
        if (enumerator == null) {
            _addresses.value = PairingAddressState.Unavailable
            return false
        }
        if (!readingAddresses.compareAndSet(false, true)) {
            return false
        }
        try {
            _addresses.value = PairingAddressState.Ready(enumerator.enumerate())
            return true
        } finally {
            readingAddresses.set(false)
        }
    }

    /** 换页或重新贴链接时清掉上一次结论。 */
    fun clear() {
        clearHeld()
        _state.value = PairingLinkState.Idle
    }

    private fun clearHeld() {
        held = null
        heldEndpoint = null
        heldPeerCard = null
        _peer.value = PairingPeerState.NotChecked
    }

    private data class Endpoint(val host: String, val port: Int)

    /** 界面上显示的对端地址（主机:端口）；解析不出来就退回原文，不抛。 */
    private fun endpointText(url: String): String {
        val endpoint = endpointOf(url) ?: return url.trim()
        return endpoint.host + ":" + endpoint.port
    }

    private fun endpointOf(url: String): Endpoint? {
        val trimmed = url.trim()
        val uri = try {
            URI(trimmed)
        } catch (unexpected: Exception) {
            // 载荷校验已经保证是绝对 http/https URL；这里只是不让一个意外的串把界面搞崩。
            return null
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme?.equals("https", ignoreCase = true) == true -> DEFAULT_HTTPS_PORT
            else -> DEFAULT_HTTP_PORT
        }
        return Endpoint(host, port)
    }

    companion object {

        /** 还没核对链接就来拉邀请（本层前置条件码，不进 core 的配对词汇表）。 */
        const val PAIRING_NOT_VERIFIED: String = "pairing-not-verified"

        /** 本次构建没有可用的拉取出口（界面预览态或未接线）。 */
        const val PAIRING_FETCH_UNAVAILABLE: String = "pairing-fetch-unavailable"

        /** 本机没有可用的已配对设备登记（与 Windows 的 registry-unavailable 同义）。 */
        const val REGISTRY_UNAVAILABLE: String = "registry-unavailable"

        private const val MILLIS_PER_SECOND: Long = 1_000L

        private const val DEFAULT_HTTPS_PORT: Int = 443

        private const val DEFAULT_HTTP_PORT: Int = 80
    }
}
