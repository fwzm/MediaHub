package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 本机已经配对过的一台设备。
 *
 * 配对过的设备不是已同步的设备：这条记录只说明「曾经完成过一次 trusted handshake，并记住了对方当时出示的指纹」。
 * 它不携带任何凭据、令牌或来源清单，写它也不会联系任何人。Windows 侧的对应实现是 MediaHub.Sync.PairedDevice。
 */
data class PairedDevice(
    /** 对端自称的稳定标识；永远不是路径、地址或账号。 */
    val deviceId: String,
    /** 配对时对端出示的名字；对端没给时为空。 */
    val displayName: String = "",
    /** 首次配对时钉住的小写十六进制指纹：下一次相见的信任锚点。 */
    val fingerprint: String = "",
    /** 首次接受配对的时间（epoch 毫秒，UTC）。 */
    val pairedAtEpochMs: Long = 0L,
    /** 最近一次完成配对握手的时间（epoch 毫秒，UTC）。 */
    val lastSeenEpochMs: Long = 0L,
    /** 对端自报的来源数量，仅作展示，从不参与身份判定。 */
    val sourceCount: Int = 0,
) {

    /** 每四位一组的指纹，便于人读；存储值无法解析时为空。 */
    val fingerprintDisplay: String
        get() {
            val parsed = PairingFingerprint.tryParse(fingerprint) ?: return ""
            return parsed.displayString
        }

    /** 展示用名字；没有名字时退回标识。 */
    val displayNameOrId: String
        get() = if (WireText.isBlank(displayName)) deviceId else displayName

    /** 一行摘要；永远不声称已经同步或已经登录。 */
    fun describe(nowEpochMs: Long? = null): String {
        val pairedAt = formatEpoch(pairedAtEpochMs)
        val seenAt = formatEpoch(lastSeenEpochMs)
        val suffix = if (nowEpochMs != null && lastSeenEpochMs > nowEpochMs) "（时间来自对端，晚于本机时钟）" else ""
        return displayNameOrId + " · 首次配对 " + pairedAt + " · 最近配对 " + seenAt + suffix + " · 来源 " + sourceCount + " 个"
    }

    companion object {
        /** 展示名超过这个长度就截断：一条记录不该被对端塞进一整段文字。 */
        const val MAXIMUM_DISPLAY_NAME_LENGTH = 128

        /** epoch 毫秒能表示的最大瞬时（与 .NET DateTimeOffset.MaxValue 相同：9999-12-31T23:59:59.999Z）。 */
        const val MAXIMUM_EPOCH_MS = 253402300799999L

        private val EPOCH_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

        /** 构造一条字段已归一化的记录；时钟由调用方提供。 */
        fun create(
            deviceId: String,
            fingerprint: String,
            displayName: String?,
            nowEpochMs: Long,
            sourceCount: Int = 0,
            pairedAtEpochMs: Long? = null,
        ): PairedDevice {
            require(!WireText.isBlank(deviceId)) { "设备标识不能为空。" }
            val normalizedDisplay = WireText.trim(displayName ?: "")
            return PairedDevice(
                deviceId = WireText.trim(deviceId),
                displayName = if (normalizedDisplay.length > MAXIMUM_DISPLAY_NAME_LENGTH) {
                    normalizedDisplay.substring(0, MAXIMUM_DISPLAY_NAME_LENGTH)
                } else {
                    normalizedDisplay
                },
                fingerprint = WireText.lowercaseAscii(WireText.trim(fingerprint)),
                pairedAtEpochMs = pairedAtEpochMs ?: nowEpochMs,
                lastSeenEpochMs = nowEpochMs,
                sourceCount = if (sourceCount < 0) 0 else sourceCount,
            )
        }

        /** 本地时区的一行时间；无法表示时给「未知」而不是一个看起来像真的日期。 */
        fun formatEpoch(epochMs: Long): String {
            if (epochMs <= 0 || epochMs > MAXIMUM_EPOCH_MS) {
                return "未知"
            }
            return try {
                EPOCH_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
            } catch (exception: DateTimeException) {
                "未知"
            }
        }
    }
}

