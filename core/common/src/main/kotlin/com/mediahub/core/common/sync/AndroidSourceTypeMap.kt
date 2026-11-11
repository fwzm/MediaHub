package com.mediahub.core.common.sync

import com.mediahub.model.ServerType

/** wire 与 Android 枚举的一行对照。 */
data class AndroidSourceTypeMapping(val wire: String, val serverType: ServerType)

/** 解析结果：始终带着原始 wire 值，另外报告本端有没有对应类型。 */
data class AndroidSourceTypeResolution(
    val sourceType: SyncSourceTypeValue,
    val serverType: ServerType?,
) {
    /** 是否属于合同词汇。 */
    val isKnown: Boolean get() = sourceType.isKnown

    /** 本端是否有对应类型（true 才能导入）。 */
    val isSupported: Boolean get() = serverType != null
}

/**
 * wire 与 Android ServerType 的显式对照表。
 *
 * 刻意手写而不是 Enum.valueOf / 名字推导：两端命名习惯不同（WebDav 与 webdav），一侧改名必须让
 * 这里的测试失败，而不是悄悄改变协议语义。
 *
 * 与 Windows 的差异（有意的）：Android 的 ServerType 覆盖了全部 12 个 wire 值，
 * 而 Windows 只覆盖 6 个（emby/jellyfin/plex/webdav/smb/local），fn-nas 与云盘在 Windows 上属于
 * "认识但本端没有类型"。因此同一条记录在 Windows 上可能得到 not-supported-on-windows，
 * 在 Android 上应当直接可用。
 */
object AndroidSourceTypeMap {

    private val ROWS: List<AndroidSourceTypeMapping> = listOf(
        AndroidSourceTypeMapping(SyncSourceTypes.EMBY, ServerType.EMBY),
        AndroidSourceTypeMapping(SyncSourceTypes.JELLYFIN, ServerType.JELLYFIN),
        AndroidSourceTypeMapping(SyncSourceTypes.PLEX, ServerType.PLEX),
        AndroidSourceTypeMapping(SyncSourceTypes.WEBDAV, ServerType.WEBDAV),
        AndroidSourceTypeMapping(SyncSourceTypes.SMB, ServerType.SMB),
        AndroidSourceTypeMapping(SyncSourceTypes.LOCAL, ServerType.LOCAL),
        AndroidSourceTypeMapping(SyncSourceTypes.FN_NAS, ServerType.FN_NAS),
        AndroidSourceTypeMapping(SyncSourceTypes.ALIYUN_DRIVE, ServerType.ALIYUN_DRIVE),
        AndroidSourceTypeMapping(SyncSourceTypes.BAIDU_DRIVE, ServerType.BAIDU_DRIVE),
        AndroidSourceTypeMapping(SyncSourceTypes.QUARK_DRIVE, ServerType.QUARK_DRIVE),
        AndroidSourceTypeMapping(SyncSourceTypes.CHINA_MOBILE_CLOUD, ServerType.CHINA_MOBILE_CLOUD),
        AndroidSourceTypeMapping(SyncSourceTypes.TIANYI_CLOUD, ServerType.TIANYI_CLOUD),
    )

    /** 对照表，按文档顺序；测试遍历它而不是相信它。 */
    val mappings: List<AndroidSourceTypeMapping> = ROWS

    /** 本端能导入的 wire 值。 */
    val supportedWireValues: List<String> = ROWS.map { it.wire }

    /** 把 wire 值解析成本端类型，值本身始终逐字保留。 */
    fun resolve(wire: String?): AndroidSourceTypeResolution = resolve(SyncSourceTypeValue.parse(wire))

    /**
     * 用一个已经解析好的 wire 值求对照，**不重新 parse**：调用方的原始拼写（raw）因此原样保留，
     * 与 Windows 的 WindowsSourceTypeMap.Resolve(record.SourceType) 同一口径。
     */
    fun resolve(value: SyncSourceTypeValue): AndroidSourceTypeResolution {
        val row = ROWS.firstOrNull { it.wire == value.wire }
        return AndroidSourceTypeResolution(value, row?.serverType)
    }

    /** 有对应类型时返回它，否则 null。 */
    fun toServerType(wire: String?): ServerType? = resolve(wire).serverType

    /** 枚举到 wire；null 表示该类型（或 null）在协议里没有取值。 */
    fun toWire(type: ServerType?): String? = ROWS.firstOrNull { it.serverType == type }?.wire
}
