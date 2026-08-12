package com.mediahub.provider.api

import com.mediahub.model.ServerType

/**
 * 所有数据源的公共最小接口（Interface Segregation，见 ADR-014）。
 *
 * 具体能力（认证/媒体库/浏览/播放/搜索/字幕/进度）由独立的可选能力接口表达，
 * 并通过 [ProviderHandle] 组合暴露给上层。Provider 只实现自己真实具备的能力，
 * 不再被迫实现用不到的方法。
 */
interface MediaProvider {
    /** 实例级：该数据源实例 id（对应 MediaServer.id） */
    val serverId: String

    /** 实例级：服务器类型（持久化键，见 ADR-015） */
    val type: ServerType

    /** 实例级：显示名（用户自定义的服务器名） */
    val displayName: String

    /** 类级：数据源类型描述（供 UI 动态渲染与展示，见 ADR-015） */
    val descriptor: ProviderDescriptor

    /** 协议级连接测试：由 Provider 自己判断该地址是否真的是自己的协议（见 ADR-019）。 */
    suspend fun testConnection(): ConnectionStatus
}
