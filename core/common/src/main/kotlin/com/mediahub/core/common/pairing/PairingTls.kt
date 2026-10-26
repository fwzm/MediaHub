package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.SourceSyncTlsReasons
import com.mediahub.core.common.sync.WireText

/**
 * 配对传输层要不要走 TLS 1.3，以及钉住哪一枚公钥指纹（W6-59b）。
 *
 * 与同步传输的 `SourceSyncTlsOptions` 同形状，但**码表不同**：配对传输只有
 * [PairingTransportReasons.TLS_VERSION] 与 [PairingTransportReasons.FINGERPRINT_MISMATCH] 两个
 * 与 TLS 有关的合同码（Windows `PairingTransportClient` 同此），所以这里不新开码集合，
 * 而是把底层握手的四条 TLS 码收敛到这两条（映射见 [pairingTlsReasonOf]）。
 *
 * 缺省是 [Off]：不传它的调用方拿到的字节与 W6-G 至今逐字一致（明文帧，一个字节都不多说）。
 */
sealed class PairingTlsOptions {

    /** 是否开启 TLS。 */
    abstract val enabled: Boolean

    /** 期望的对端指纹（原样保存，可能不是合法指纹；合法性在拨号前判定）。 */
    abstract val expectedFingerprint: String?

    /** 关：字节与 W6-G 至今完全一致。 */
    object Off : PairingTlsOptions() {
        override val enabled: Boolean get() = false
        override val expectedFingerprint: String? get() = null

        override fun toString(): String = "PairingTlsOptions { Enabled = false }"
    }

    /**
     * 开 TLS 1.3 并钉住指纹。
     *
     * @param pin 64 位 hex（大小写不敏感）；不合法的指纹不在这里抛，拨号前判为
     *   [PairingReasons.BAD_FINGERPRINT]——与 Windows 一致（构造不校验）。
     */
    class Pinned(pin: String) : PairingTlsOptions() {
        override val enabled: Boolean get() = true
        override val expectedFingerprint: String = pin

        /** 解析后的指纹；不合法时为 null。 */
        val fingerprint: PairingFingerprint? = PairingFingerprint.tryParse(pin)

        override fun toString(): String =
            "PairingTlsOptions { Enabled = true, ExpectedFingerprint = <redacted> }"
    }

    /** 有可用指纹。 */
    val hasPin: Boolean
        get() = this is Pinned && fingerprint != null

    companion object {
        /** 有指纹就钉住它，空白给一个不可用的 Pinned（拨号前判 bad-fingerprint，不悄悄降级成明文）。 */
        fun pinned(pin: String?): PairingTlsOptions =
            if (pin == null || WireText.isBlank(pin)) Pinned("") else Pinned(pin)
    }
}

/**
 * 把底层 TLS 握手码收敛成配对传输的两条合同码。
 *
 * Windows 侧的映射是逐条算出来的（PairingTransportClient.ConnectAsync 的 catch 链）：
 * 指纹不符给 fingerprint-mismatch；没有证书、协议不是 1.3、握手被拒、握手超时，全都给 tls-version。
 * 所以这里只有一条特例，其余一律 tls-version——不新开码、不改语义。
 */
internal fun pairingTlsReasonOf(tlsReason: String): String = when (tlsReason) {
    SourceSyncTlsReasons.FINGERPRINT_MISMATCH -> PairingTransportReasons.FINGERPRINT_MISMATCH
    else -> PairingTransportReasons.TLS_VERSION
}

/** 配对 TLS 的 SNI / 目标主机名；与 Windows `PairingTransportClient.TargetHostName` 逐字一致。 */
const val PAIRING_TLS_TARGET_HOST_NAME: String = "mediahub-pairing"
