package com.mediahub.provider.base

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 默认 Provider 注册表：由 Hilt 多绑定（@IntoSet）注入所有 [MediaProviderFactory]，
 * 内部按键（[ServerType]）建立索引；新增数据源只需注册新工厂，无需改动本类。
 *
 * 说明（ADR-005）：注册采用 @IntoSet + 工厂自报 serverType，
 * 不使用 @IntoMap（Hilt KSP 在当前工具链下对 @IntoMap 处理存在 bug，见 DECISIONS.md）。
 */
@Singleton
class DefaultProviderRegistry @Inject constructor(
    factories: Set<@JvmSuppressWildcards MediaProviderFactory>,
) : MediaProviderRegistry {

    private val factoryMap: Map<ServerType, MediaProviderFactory> =
        factories.associateBy { it.serverType }

    override fun factoryFor(type: ServerType): MediaProviderFactory? = factoryMap[type]

    override fun create(server: MediaServer): MediaProvider? = factoryMap[server.type]?.create(server)

    override val supportedTypes: Set<ServerType> get() = factoryMap.keys
}
