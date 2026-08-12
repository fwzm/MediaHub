package com.mediahub.provider.emby

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
class EmbyProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
    private val tokenStore: TokenStore,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = EMBY_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val provider = EmbyProvider(
            server = server,
            apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger),
            tokenStore = tokenStore,
            logger = logger,
        )
        // ADR-022：Handle 只暴露"当前版本真正实现完成"的能力。
        // Emby 能力（登录/媒体库/播放/进度…）为 Phase 1 占位，当前不放 Handle；
        // 每实现一项，在 Phase 1 把对应字段填上。
        return ProviderHandle(provider = provider)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbyProviderModule {
    @Binds
    @IntoSet
    abstract fun bindEmbyProviderFactory(factory: EmbyProviderFactory): MediaProviderFactory
}
