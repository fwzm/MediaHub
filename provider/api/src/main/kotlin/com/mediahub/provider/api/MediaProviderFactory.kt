package com.mediahub.provider.api

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType

/**
 * Provider 工厂：为某个 [MediaServer] 创建 [MediaProvider] 实例。
 * 具体实现注册进 [MediaProviderRegistry]（Hilt @IntoMap，键为 [ServerType]）。
 */
interface MediaProviderFactory {
    val serverType: ServerType
    fun create(server: MediaServer): MediaProvider
}

/** 工厂注册表（UI / UseCase 通过它拿到 Provider，无需感知具体类型）。 */
interface MediaProviderRegistry {
    fun factoryFor(type: ServerType): MediaProviderFactory?
    fun create(server: MediaServer): MediaProvider?
    val supportedTypes: Set<ServerType>
}
