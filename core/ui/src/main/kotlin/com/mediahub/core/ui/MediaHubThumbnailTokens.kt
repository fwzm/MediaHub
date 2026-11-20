package com.mediahub.core.ui

/**
 * 统一缩略图尺寸 token（视觉审计 §3.3）。
 *
 * 列表/详情页的图片宽度统一引用这里，禁止再写 120/96/110 等一次性值。
 * 值以 dp 为单位，使用方以 TOKEN.dp 转为 Dp。
 */
object MediaHubThumbnailTokens {
    /** 列表竖版海报（2:3）宽度。 */
    const val POSTER_WIDTH_DP = 96

    /** 详情页 hero 竖版海报（2:3）宽度。 */
    const val POSTER_HERO_WIDTH_DP = 104

    /** 横版缩略图（16:9）宽度，用于单集/视频列表。 */
    const val THUMB_16X9_WIDTH_DP = 96
}
