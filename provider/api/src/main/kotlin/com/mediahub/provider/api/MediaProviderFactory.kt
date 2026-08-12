package com.mediahub.provider.api

import com.mediahub.model.MediaServer

/**
 * Provider 工厂：为某个 [MediaServer] 创建 [ProviderHandle]。
 * 具体实现通过 Hilt @IntoSet 注册，Registry 使用开放的稳定 providerId 建索引。
 */
interface MediaProviderFactory {
    val descriptor: ProviderDescriptor
    fun create(server: MediaServer): ProviderHandle
}

/** 工厂注册表（UI / UseCase 通过它拿到 Provider，无需感知具体类型）。 */
interface MediaProviderRegistry {
    fun factoryFor(providerId: String): MediaProviderFactory?
    fun descriptorFor(providerId: String): ProviderDescriptor?
    fun create(server: MediaServer): ProviderHandle?
    val descriptors: List<ProviderDescriptor>
}
