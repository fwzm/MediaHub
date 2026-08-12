package com.mediahub.provider.webdav

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
class WebDavProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
    private val tokenStore: TokenStore,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = WEBDAV_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val provider = WebDavProvider(
            server = server,
            apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger),
            tokenStore = tokenStore,
            logger = logger,
        )
        return ProviderHandle(
            provider = provider,
            auth = provider,
            browse = provider,
            search = provider,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavProviderModule {
    @Binds
    @IntoSet
    abstract fun bindWebDavProviderFactory(factory: WebDavProviderFactory): MediaProviderFactory
}
