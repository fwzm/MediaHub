package com.mediahub.metadata

/** 按 [MetadataSource] 解析已装配的 Provider（未注册的源返回 null，由调用方降级）。 */
class DefaultMetadataRegistry(
    providers: List<MetadataProvider>,
) : MetadataRegistry {

    private val bySource: Map<MetadataSource, MetadataProvider> = providers.associateBy { it.source }

    override fun providerFor(source: MetadataSource): MetadataProvider? = bySource[source]
}
