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
        // 装配期不变量：同一 ServerType / 同一 provider id 只允许注册一次。
        // 失败信息必须点名冲突的类型与双方 id —— 基线只有固定串 "Duplicate provider server type"，
        // 装配方无从判断是哪个数据源被重复 @IntoSet 贡献，只能逐个模块翻代码。
        val duplicateTypes = registrations.groupBy { it.first.serverType }.filterValues { it.size > 1 }
        require(duplicateTypes.isEmpty()) {
            "重复的 Provider serverType：" + duplicateTypes.entries.sortedBy { it.key.name }
                .joinToString("; ") { (type, group) ->
                    "$type <- " + group.joinToString(", ") { it.first.id }
                } + "；每个 ServerType 只允许一个 Factory（请检查 @IntoSet 多绑定是否重复贡献）"
        }
        val duplicateIds = registrations.groupBy { it.first.id }.filterValues { it.size > 1 }
        require(duplicateIds.isEmpty()) {
            "重复的 Provider id：" + duplicateIds.keys.sorted().joinToString(", ") +
                "；provider id 是稳定标识，不允许两个 Factory 共用"
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
