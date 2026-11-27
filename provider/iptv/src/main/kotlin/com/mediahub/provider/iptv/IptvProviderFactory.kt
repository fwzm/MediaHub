package com.mediahub.provider.iptv

import com.mediahub.core.network.HttpClientFactory
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IPTV Provider 工厂。
 *
 * 装配能力（ADR-022：Handle 只放真正实现完成的能力）：
 * LIBRARY + PLAYBACK + SEARCH + EPG。不含 AUTH（M3U 地址即资源标识）、
 * PROGRESS（直播无续播语义）、FAVORITE（收藏在 feature 层本地维护）。
 */
@Singleton
class IptvProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = IPTV_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val repository = IptvPlaylistRepository(
            serverId = server.id,
            client = httpClientFactory.apiClient(),
        )
        val provider = IptvProvider(server = server, repository = repository)
        return ProviderHandle(
            provider = provider,
            library = provider,
            playback = provider,
            search = provider,
            epg = provider,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IptvProviderModule {
    @Binds
    @IntoSet
    abstract fun bindIptvProviderFactory(factory: IptvProviderFactory): MediaProviderFactory
}
