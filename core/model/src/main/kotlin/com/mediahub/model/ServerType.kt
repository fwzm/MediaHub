package com.mediahub.model

/**
 * 数据源类型。新增数据源时在此扩展，并在 [com.mediahub.provider.api.MediaProviderFactory]
 * 注册对应工厂，UI 无需改动。
 */
enum class ServerType(val label: String, val category: Category) {
    EMBY("Emby", Category.MEDIA_SERVER),
    JELLYFIN("Jellyfin", Category.MEDIA_SERVER),
    PLEX("Plex", Category.MEDIA_SERVER),
    FN_NAS("FnNas", Category.MEDIA_SERVER),
    WEBDAV("WebDAV", Category.CLOUD_STORAGE),
    SMB("SMB", Category.CLOUD_STORAGE),
    LOCAL("本地存储", Category.CLOUD_STORAGE),
    ALIYUN_DRIVE("阿里云盘", Category.CLOUD_DRIVE),
    BAIDU_DRIVE("百度网盘", Category.CLOUD_DRIVE),
    QUARK_DRIVE("夸克网盘", Category.CLOUD_DRIVE),
    CHINA_MOBILE_CLOUD("中国移动云盘", Category.CLOUD_DRIVE),
    TIANYI_CLOUD("中国电信天翼云盘", Category.CLOUD_DRIVE);

    enum class Category { MEDIA_SERVER, CLOUD_STORAGE, CLOUD_DRIVE }
}
