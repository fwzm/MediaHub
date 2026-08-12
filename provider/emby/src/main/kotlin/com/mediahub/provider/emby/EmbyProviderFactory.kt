package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.session.EmbySessionValidator
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
    private val clientIdentity: ClientIdentity,
    private val logger: Logger,
) : MediaProviderFactory {
    override val descriptor = EmbyProvider.DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger)
        val authorization = EmbyAuthorizationHeaderBuilder(clientIdentity)
        val embyApi = EmbyApiClient(server.baseUrl, apiClient, authorization)
        val provider = EmbyProvider(
            server = server,
            apiClient = apiClient,
            mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger),
            logger = logger,
            embyApi = embyApi,
        )
        return ProviderHandle(
            provider = provider,
            auth = EmbyAuthProvider(server, embyApi, EmbySessionValidator(server, embyApi), logger),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbyProviderModule {
    @Binds
    @IntoSet
    abstract fun bindEmbyProviderFactory(factory: EmbyProviderFactory): MediaProviderFactory
}
