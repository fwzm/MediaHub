package com.mediahub.core.common.sync

/**
 * 同步层自有的 sourceType 词汇（ADR-S001 D3）。
 *
 * 这 12 个字符串属于同步协议，不是任何一端的枚举名：Windows 的 ServerType 与 Android 的
 * com.mediahub.model.ServerType 命名习惯不同（WebDav 与 webdav），任何一端重命名都不得改变
 * 这些字面量。顺序即文档顺序，也用于 capabilities.sourceTypes 的稳定排列。
 */
object SyncSourceTypes {
    const val EMBY: String = "emby"
    const val JELLYFIN: String = "jellyfin"
    const val PLEX: String = "plex"
    const val WEBDAV: String = "webdav"
    const val SMB: String = "smb"
    const val LOCAL: String = "local"
    const val FN_NAS: String = "fn-nas"
    const val ALIYUN_DRIVE: String = "aliyun-drive"
    const val BAIDU_DRIVE: String = "baidu-drive"
    const val QUARK_DRIVE: String = "quark-drive"
    const val CHINA_MOBILE_CLOUD: String = "china-mobile-cloud"
    const val TIANYI_CLOUD: String = "tianyi-cloud"

    /** 合同词汇全集，按 ADR-S001 D3 的列举顺序。 */
    val ALL: List<String> = listOf(
        EMBY,
        JELLYFIN,
        PLEX,
        WEBDAV,
        SMB,
        LOCAL,
        FN_NAS,
        ALIYUN_DRIVE,
        BAIDU_DRIVE,
        QUARK_DRIVE,
        CHINA_MOBILE_CLOUD,
        TIANYI_CLOUD,
    )

    /** 是否为合同词汇成员：先 trim 再忽略大小写比较（与 Windows SyncSourceTypes.IsKnown 同义）。 */
    fun isKnown(raw: String?): Boolean {
        if (raw == null) return false
        val wire = raw.trim().lowercase()
        return wire.isNotEmpty() && wire in ALL
    }
}
