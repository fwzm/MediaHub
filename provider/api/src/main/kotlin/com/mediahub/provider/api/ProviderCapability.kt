package com.mediahub.provider.api

/**
 * Provider 能力声明。UI 通过 capability 决定是否展示入口，
 * 禁止通过 "if type == EMBY" 之类的判断。
 */
enum class ProviderCapability {
    /** 用户名密码 / Token 认证 */
    AUTH,

    /** 媒体库浏览（库/文件夹/详情） */
    LIBRARY,

    /** 文件树浏览（云盘 / NAS） */
    BROWSE,

    /** 将统一 MediaItem 解析为可播放资源 */
    PLAYBACK,

    /** 搜索 */
    SEARCH,

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
