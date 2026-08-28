package com.mediahub.provider.api

/**
 * Provider 能力声明。
 *
 * 两个语义（见 ADR-014 / ADR-022）：
 * - `ProviderDescriptor.declaredCapabilities`：该类型**最终计划**支持的能力（展示/路由）。
 * - `ProviderHandle.runtimeCapabilities`：**当前版本真正实现完成**、运行时可用的能力
 *   （由 Handle 可空字段推导，是 feature 层判断的唯一权威）。
 */
enum class ProviderCapability {
    /** 用户名密码 / Token 认证 */
    AUTH,

    /** 媒体库浏览（库/条目/季/集） */
    LIBRARY,

    /** 条目详情 */
    DETAIL,

    /** 文件树浏览（云盘 / NAS / 本地） */
    BROWSE,

    /** 播放（resolvePlayback） */
    PLAYBACK,

    /** 搜索 */
    SEARCH,

    /** 查询管道（服务端排序，Phase 1C-2；未来筛选在此演进） */
    QUERY,

    /** 字幕 */
    SUBTITLE,

    /** 播放进度同步 */
    PROGRESS,

    /** 多版本（同一媒体的多个文件） */
    MULTI_VERSION,

    /** 收藏 */
    FAVORITE,

    /** 直播 */
    LIVE_TV,

    /** 服务端转码 */
    TRANSCODE,
}
