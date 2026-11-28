package com.mediahub.feature.detail

/**
 * 详情页稳定的语义标识：生产 UI 与 Compose 验收测试共用。
 * 主播放按钮的文案会随续播快照变化（「立即播放」/「继续播放 · mm:ss」），
 * 断言必须绑标识而不是绑文案。
 */
object DetailTestTags {
    const val PRIMARY_PLAY_ACTION = "detail_primary_play_action"
}
