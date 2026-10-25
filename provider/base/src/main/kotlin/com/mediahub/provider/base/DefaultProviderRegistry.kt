package com.mediahub.provider.base

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 默认 Provider 注册表：由 Hilt 多绑定（@IntoSet）注入所有 [MediaProviderFactory]，
 * 内部按键（[ServerType]）建立索引（见 ADR-005、ADR-015）。
 *
 * 新增数据源 = 新增 Factory + @IntoSet 绑定；UI 通过 [descriptors] 动态渲染，
 * 无需修改核心逻辑。
 */
@Singleton
class DefaultProviderRegistry @Inject constructor(
    factories: Set<@JvmSuppressWildcards MediaProviderFactory>,
) : MediaProviderRegistry {

    // Read each descriptor once so validation and indexing use the same registration.
    private val registrations = factories.map { it.descriptor to it }

    init {
        require(registrations.map { it.first.serverType }.toSet().size == registrations.size) {
            "Duplicate provider server type"
        }
        require(registrations.map { it.first.id }.toSet().size == registrations.size) {
            "Duplicate provider stable id"
        }
    }

    private val factoryMap: Map<ServerType, MediaProviderFactory> =
        java.util.Collections.unmodifiableMap(registrations.associate { it.first.serverType to it.second })

    override fun factoryFor(type: ServerType): MediaProviderFactory? = factoryMap[type]

    override fun create(server: MediaServer): ProviderHandle? =
        factoryMap[server.type]?.create(server)

    override val supportedTypes: Set<ServerType> get() = factoryMap.keys

    override fun descriptors(): List<ProviderDescriptor> =
        registrations.map { it.first }
            .sortedBy { it.displayName }
}
