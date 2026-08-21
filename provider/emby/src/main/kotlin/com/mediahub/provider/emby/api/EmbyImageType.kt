package com.mediahub.provider.emby.api

/** Emby 图片类型（/Items/{id}/Images/{type} 路径段）。Phase 1B-2.3 只用三类。 */
enum class EmbyImageType(val wireName: String) {
    /** 主图：电影/剧集/季的竖版海报；单集的横版剧照。 */
    PRIMARY("Primary"),

    /** 缩略图（部分单集有，通常为 16:9 横版）。 */
    THUMB("Thumb"),

    /** 背景图（fanart，横版大图，详情页顶部）。 */
    BACKDROP("Backdrop"),
}
