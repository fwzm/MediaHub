package com.mediahub.provider.jellyfin

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
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

@Singleton
class JellyfinProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
    private val tokenStore: TokenStore,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = JELLYFIN_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val provider = JellyfinProvider(
            server = server,
            apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger),
            tokenStore = tokenStore,
            logger = logger,
        )
        // ADR-022：Handle 只暴露"当前版本真正实现完成"的能力（Phase 1 逐项填充）。
        return ProviderHandle(provider = provider)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinProviderModule {
    @Binds
    @IntoSet
    abstract fun bindJellyfinProviderFactory(factory: JellyfinProviderFactory): MediaProviderFactory
}
