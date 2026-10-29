package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncLocalReasons

/**
 * W6-106：监听（接收）路径的固定日志措辞。
 *
 * 一封信封从对端到达本机要经过「开监听 → 等一次连接 → 收帧 → 判信封」四步，每一步都可能失败或被打断。
 * 界面卡片会被下一条状态覆盖、进程会被回收，所以每一步都留一行日志；措辞只写在这里，
 * [SourceSyncListenerController] 只负责在正确的时刻调用。
 *
 * 口径与 W6-101/104 相同：只记本端稳定的码与身份。**不记**口令、证书指纹、任务预算、
 * 信封正文、拒收 message 与异常措辞 —— 那些要么是凭据，要么只有界面需要（见 docs §75）。
 */
object SourceSyncListenLog {

    /** 对端没报身份时的占位：不写空串，免得日志里出现 `device=` 这种半句。 */
    const val UNKNOWN_DEVICE: String = "unknown-device"

    const val STARTED_PREFIX: String = "sync-listen-started:"

    const val RECEIVED_PREFIX: String = "sync-listen-received:"

    const val REJECTED_PREFIX: String = "sync-listen-rejected:"

    const val FAILED_PREFIX: String = "sync-listen-failed:"

    const val CANCELLED_PREFIX: String = "sync-listen-cancelled:"

    const val STOPPED_PREFIX: String = "sync-listen-stopped:"

    /** 监听开了：只报端口与是否 TLS，不报指纹。 */
    fun started(port: Int, tls: Boolean): String = STARTED_PREFIX + "port=" + port + ":tls=" + tls

    /** 收下一封信封：报对端身份（缺省占位）与是否真的写回了回执。 */
    fun received(deviceId: String?, receiptWritten: Boolean): String {
        val device = deviceId?.trim().orEmpty()
        return RECEIVED_PREFIX + "device=" + device.ifEmpty { UNKNOWN_DEVICE } + ":receipt=" + receiptWritten
    }

    /** 帧层成功但载荷不是合法信封：只报 reason，message 留在界面。 */
    fun rejected(reason: String): String = REJECTED_PREFIX + "reason=" + reason.trim()

    /** 任意一步失败：只报稳定码，detail 留在界面。 */
    fun failed(code: String): String = FAILED_PREFIX + "code=" + code.trim()

    /** 一次等待被本地取消：报累计次数与最近一次的码（缺省是本地码 transfer-cancelled）。 */
    fun cancelled(count: Int, reason: String?): String {
        val text = reason?.trim().orEmpty()
        return CANCELLED_PREFIX + "count=" + count + ":reason=" + text.ifEmpty { SourceSyncLocalReasons.CANCELLED }
    }

    /** 关掉监听（幂等 stop 里真的关了那一次才记）。 */
    fun stopped(port: Int): String = STOPPED_PREFIX + "port=" + port
}
