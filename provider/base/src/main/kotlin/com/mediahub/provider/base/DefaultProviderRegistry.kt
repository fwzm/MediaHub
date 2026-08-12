package com.mediahub.provider.base

import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderDescriptor
import javax.inject.Inject
import javax.inject.Singleton

/** Hilt @IntoSet 注册的工厂索引；键为开放的稳定 providerId。 */
@Singleton
class DefaultProviderRegistry @Inject constructor(
    factories: Set<@JvmSuppressWildcards MediaProviderFactory>,
) : MediaProviderRegistry {

    private val factoryMap: Map<String, MediaProviderFactory> = factories
        .groupBy { it.descriptor.providerId }
        .also { grouped ->
            val duplicates = grouped.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "ProviderFactory 重复注册：${duplicates.sorted()}" }
        }
        .mapValues { it.value.single() }

    private val descriptorMap: Map<String, ProviderDescriptor> =
        PlannedProviderCatalog.descriptors.associateBy { it.providerId } +
            factoryMap.mapValues { it.value.descriptor }

    override fun factoryFor(providerId: String): MediaProviderFactory? = factoryMap[providerId]

    override fun descriptorFor(providerId: String): ProviderDescriptor? = descriptorMap[providerId]

    override fun create(server: MediaServer) = factoryMap[server.providerId]?.create(server)

    override val descriptors: List<ProviderDescriptor> = descriptorMap.values
        .sortedWith(compareBy(ProviderDescriptor::sortOrder, ProviderDescriptor::displayName))
}
