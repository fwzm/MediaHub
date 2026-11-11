package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 本机在配对时的自报身份（W6-71）。
 *
 * 与 Windows `MediaHub.Sync.PairingSelfIdentity` 逐字节对齐：指纹不是随机生成的，而是
 * `SHA-256("mediahub-device:" + trim(deviceId))` 的 32 字节。这样同一个 deviceId 在任何一次配对里
 * 都得到同一个指纹，对端才能把它当成「上次那台是不是同一台」的钉扎锚点。
 *
 * 与邀请里的 publicKeyFingerprint 不是一回事：那是每次配对临时自签证书的指纹，收完即弃；
 * 卡片里这个才是稳定的设备指纹，登记只能用后者。
 */
data class PairingSelfIdentity(
    /** 本机稳定标识（trim 之后）。 */
    val deviceId: String,
    /** 由 [deviceId] 派生出来的稳定指纹。 */
    val fingerprint: PairingFingerprint,
    /** 本机人类可读名字；没有时为空串。 */
    val displayName: String = "",
    /** 本机来源数量（仅展示）。 */
    val sourceCount: Int = 0,
) {

    /** 把本机身份变成要发给对端的卡片。 */
    fun toCard(): PairingPeerCard = PairingPeerCard(
        deviceId = deviceId,
        deviceFingerprint = fingerprint.hex,
        displayName = displayName,
        sourceCount = sourceCount,
    )

    companion object {
        /** 指纹派生用的域前缀；两端必须逐字相同，否则同一台设备在两端算出不同指纹。 */
        const val DERIVATION_PREFIX: String = "mediahub-device:"

        /** 由 deviceId 构造本机身份。deviceId 空白直接拒绝（没有标识就无法钉扎）。 */
        fun create(deviceId: String?, displayName: String? = null, sourceCount: Int = 0): PairingSelfIdentity {
            val key = WireText.trim(deviceId ?: "")
            require(key.isNotEmpty()) { "设备标识不能为空。" }
            return PairingSelfIdentity(
                deviceId = key,
                fingerprint = deriveFingerprint(key),
                displayName = WireText.trim(displayName ?: ""),
                sourceCount = if (sourceCount < 0) 0 else sourceCount,
            )
        }

        /** 稳定指纹 = SHA-256(UTF-8(DERIVATION_PREFIX + trim(deviceId)))。 */
        fun deriveFingerprint(deviceId: String?): PairingFingerprint {
            val key = WireText.trim(deviceId ?: "")
            require(key.isNotEmpty()) { "设备标识不能为空。" }
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest((DERIVATION_PREFIX + key).toByteArray(StandardCharsets.UTF_8))
            return PairingFingerprint.fromBytes(hash)
        }
    }
}
