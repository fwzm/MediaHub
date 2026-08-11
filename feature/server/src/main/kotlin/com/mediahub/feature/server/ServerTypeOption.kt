package com.mediahub.feature.server

import com.mediahub.model.ServerType

/** 添加媒体库时的可选类型（Phase 0 仅开放已注册 Provider 的类型）。 */
data class ServerTypeOption(
    val type: ServerType,
    val enabled: Boolean,
    val description: String,
)

val ServerTypeOptions: List<ServerTypeOption> = listOf(
    ServerTypeOption(ServerType.EMBY, true, "媒体服务器（Emby）"),
    ServerTypeOption(ServerType.JELLYFIN, true, "媒体服务器（Jellyfin）"),
    ServerTypeOption(ServerType.WEBDAV, true, "WebDAV / NAS 通用协议"),
    ServerTypeOption(ServerType.LOCAL, true, "本机存储（应用目录）"),
    ServerTypeOption(ServerType.PLEX, false, "后续版本"),
    ServerTypeOption(ServerType.FN_NAS, false, "后续版本"),
    ServerTypeOption(ServerType.SMB, false, "后续版本"),
    ServerTypeOption(ServerType.ALIYUN_DRIVE, false, "后续版本"),
    ServerTypeOption(ServerType.BAIDU_DRIVE, false, "后续版本"),
    ServerTypeOption(ServerType.QUARK_DRIVE, false, "后续版本"),
    ServerTypeOption(ServerType.CHINA_MOBILE_CLOUD, false, "后续版本"),
    ServerTypeOption(ServerType.TIANYI_CLOUD, false, "后续版本"),
)
