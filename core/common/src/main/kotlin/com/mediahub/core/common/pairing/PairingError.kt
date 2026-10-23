package com.mediahub.core.common.pairing

/**
 * 配对契约的稳定原因码，与 Windows 侧 MediaHub.Sync 逐字相同（ADR-S003）。
 *
 * 这些字符串会进 UI 翻译表、支持日志与测试，改动即破坏契约。Windows 对照文件：
 * PairingPayload.cs 的 PairingReasons、PairingCode.cs 的 PairingCodeReasons、
 * PairingLink.cs 的 PairingLinkReasons、PairingTransportReasons.cs、
 * PairingAddressCandidates.cs 的 PairingAddressReasons。
 *
 * Android 侧只实现契约与纯计算部分；真实 TLS 传输、二维码/扫码与 UI 不在本包内。
 */
object PairingReasons {
    /** 载荷 schemaVersion 不是本端能读的版本。 */
    const val UNSUPPORTED_VERSION: String = "unsupported-version"

    /** 公钥指纹不是 32 字节小写 hex。 */
    const val BAD_FINGERPRINT: String = "bad-fingerprint"

    /** challenge 不是 32 字节规范 base64。 */
    const val BAD_CHALLENGE: String = "bad-challenge"

    /** 临时地址不是绝对 http/https、带 user-info、或带 query/fragment。 */
    const val BAD_URL: String = "bad-url"

    /** 邀请已过期（ExpiresAtEpochMs <= now，等于也算过期）。 */
    const val EXPIRED: String = "expired"

    /** challenge 已被消费过（一次性）。 */
    const val CHALLENGE_REPLAYED: String = "challenge-replayed"

    /** 对端回传的 challenge 与本端发出的不一致。 */
    const val CHALLENGE_MISMATCH: String = "challenge-mismatch"

    /** 文档不是合法 JSON 或不是 JSON 对象。 */
    const val MALFORMED_JSON: String = "malformed-json"

    /** 字段缺失或空白时的前缀，完整原因是 missing-field:<name>。 */
    const val MISSING_FIELD_PREFIX: String = "missing-field:"

    /** 字段类型/取值非法时的前缀，完整原因是 invalid-field:<name>。 */
    const val INVALID_FIELD_PREFIX: String = "invalid-field:"

    /** 七个合同原因码，顺序与 Windows PairingReasons.All 一致。 */
    val ALL: List<String> = listOf(
        UNSUPPORTED_VERSION,
        BAD_FINGERPRINT,
        BAD_CHALLENGE,
        BAD_URL,
        EXPIRED,
        CHALLENGE_REPLAYED,
        CHALLENGE_MISMATCH,
    )

    /** missing-field:<name>。 */
    fun missingField(name: String): String = MISSING_FIELD_PREFIX + name

    /** invalid-field:<name>。 */
    fun invalidField(name: String): String = INVALID_FIELD_PREFIX + name
}

/** 配对码（base64url 文本）的拒绝原因，与 Windows PairingCodeReasons 一致。 */
object PairingCodeReasons {
    const val EMPTY: String = "code-empty"
    const val NOT_BASE64URL: String = "not-base64url"
    const val TOO_LONG: String = "code-too-long"
    const val PAYLOAD_INVALID: String = "payload-invalid"

    /** 以下五个码由载荷契约透传，不另造 Android 专用码。 */
    const val UNSUPPORTED_VERSION: String = PairingReasons.UNSUPPORTED_VERSION
    const val BAD_CHALLENGE: String = PairingReasons.BAD_CHALLENGE
    const val BAD_FINGERPRINT: String = PairingReasons.BAD_FINGERPRINT
    const val BAD_URL: String = PairingReasons.BAD_URL
    const val EXPIRED: String = PairingReasons.EXPIRED

    /** 九个码，顺序与 Windows PairingCodeReasons.All 一致。 */
    val ALL: List<String> = listOf(
        EMPTY,
        NOT_BASE64URL,
        TOO_LONG,
        PAYLOAD_INVALID,
        UNSUPPORTED_VERSION,
        BAD_CHALLENGE,
        BAD_FINGERPRINT,
        BAD_URL,
        EXPIRED,
    )
}

/** 配对链接（mediahub://pair?...）的拒绝原因，与 Windows PairingLinkReasons 一致。 */
object PairingLinkReasons {
    const val EMPTY: String = "pairing-link-empty"
    const val NOT_A_LINK: String = "not-a-pairing-link"
    const val TOO_LONG: String = "pairing-link-too-long"

    val ALL: List<String> = listOf(EMPTY, NOT_A_LINK, TOO_LONG)
}

/**
 * 配对传输层的原因码，与 Windows PairingTransportReasons 一致。
 *
 * Android 侧本轮只冻结码表（真实 TLS 1.3 传输未实现），但码值必须同名同义：
 * 同一行日志里可能同时出现载荷码与传输码。
 */
object PairingTransportReasons {
    const val LISTENER_FAILED: String = "listener-failed"
    const val TLS_VERSION: String = "tls-version"
    const val FINGERPRINT_MISMATCH: String = "fingerprint-mismatch"

    /** 帧缺失、截断、超长或内部不是合法配对载荷。 */
    const val PAYLOAD_INVALID: String = "payload-invalid"
    const val LIFETIME_EXCEEDED: String = "lifetime-exceeded"
    const val ALREADY_PAIRED: String = "already-paired"
    const val PEER_TIMEOUT: String = "peer-timeout"

    val ALL: List<String> = listOf(
        LISTENER_FAILED,
        TLS_VERSION,
        FINGERPRINT_MISMATCH,
        PAYLOAD_INVALID,
        LIFETIME_EXCEEDED,
        ALREADY_PAIRED,
        PEER_TIMEOUT,
    )
}

/** 局域网候选地址的排序/拒绝原因，与 Windows PairingAddressReasons 一致。 */
object PairingAddressReasons {
    const val WIRED: String = "wired-interface"
    const val WIRELESS: String = "wireless-interface"
    const val OTHER: String = "other-interface"
    const val LOOPBACK: String = "loopback-address"
    const val APIPA: String = "apipa-address"
    const val INTERFACE_DOWN: String = "interface-down"
    const val NOT_IPV4: String = "not-ipv4"
    const val NO_USABLE_ADDRESS: String = "no-usable-address"
    const val ENUMERATION_FAILED: String = "address-enumeration-failed"

    val ALL: List<String> = listOf(
        WIRED,
        WIRELESS,
        OTHER,
        LOOPBACK,
        APIPA,
        INTERFACE_DOWN,
        NOT_IPV4,
        NO_USABLE_ADDRESS,
        ENUMERATION_FAILED,
    )
}

/**
 * 配对契约被违反时抛出的异常：reason 是稳定的机器可读码，message 只给人看。
 *
 * 与 Windows SyncContractException 同构（同样是 reason + message 两段）。
 */
class PairingContractException(
    val reason: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 日志里也不要展开 message：message 可能引用了 challenge 等敏感值。 */
    override fun toString(): String = "PairingContractException(" + reason + ")"

    companion object {
        fun malformedJson(detail: String): PairingContractException =
            PairingContractException(PairingReasons.MALFORMED_JSON, detail)

        fun missingField(name: String): PairingContractException =
            PairingContractException(PairingReasons.missingField(name), "配对载荷缺少必填字段 " + name)

        fun invalidField(name: String, detail: String): PairingContractException =
            PairingContractException(PairingReasons.invalidField(name), "配对载荷字段 " + name + " 非法：" + detail)

        fun unsupportedVersion(actual: Int, expected: Int): PairingContractException =
            PairingContractException(
                PairingReasons.UNSUPPORTED_VERSION,
                "载荷 schemaVersion=" + actual + "，本端只读 " + expected,
            )

        fun badChallenge(detail: String): PairingContractException =
            PairingContractException(PairingReasons.BAD_CHALLENGE, detail)

        fun badFingerprint(detail: String): PairingContractException =
            PairingContractException(PairingReasons.BAD_FINGERPRINT, detail)

        fun badUrl(detail: String): PairingContractException =
            PairingContractException(PairingReasons.BAD_URL, detail)

        fun expired(expiresAtEpochMs: Long, nowEpochMs: Long): PairingContractException =
            PairingContractException(
                PairingReasons.EXPIRED,
                "邀请在 " + expiresAtEpochMs + " ms 过期，当前 " + nowEpochMs + " ms",
            )
    }
}
