package com.mediahub.provider.api

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType

/**
 * Provider 工厂：为某个 [MediaServer] 创建 [ProviderHandle]。
 * Factory 自报 [descriptor]，UI 通过 Registry 动态读取（见 ADR-015）。
 */
interface MediaProviderFactory {
    val descriptor: ProviderDescriptor

    /** config 当前使用 [MediaServer]；未来需要线路/代理等配置时演进为 ProviderConfig（见 ADR-015）。 */
    fun create(server: MediaServer): ProviderHandle
}

/** 工厂注册表（UI / UseCase 通过它获取 Handle 与描述信息）。 */
interface MediaProviderRegistry {
    fun factoryFor(type: ServerType): MediaProviderFactory?

    /** 创建句柄；未注册的类型返回 null。 */
    fun create(server: MediaServer): ProviderHandle?

    val supportedTypes: Set<ServerType>

    /** 全部已注册 Provider 的描述（"添加媒体库"页面据此动态渲染，见 ADR-015）。 */
    fun descriptors(): List<ProviderDescriptor>
}
