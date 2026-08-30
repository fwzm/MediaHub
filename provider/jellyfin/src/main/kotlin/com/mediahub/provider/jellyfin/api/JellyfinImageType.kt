package com.mediahub.provider.jellyfin.api

/** Jellyfin 图片类型（wire 名，见 /Items/{itemId}/Images/{type}）。 */
enum class JellyfinImageType(val wireName: String) {
    PRIMARY("Primary"),
    THUMB("Thumb"),
    BACKDROP("Backdrop"),
}
