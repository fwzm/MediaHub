package com.mediahub.provider.jellyfin

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.MediaProviderFactory
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
    private val credentialVault: CredentialVault,
    private val logger: Logger,
) : MediaProviderFactory {
    override val descriptor = JellyfinProvider.DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val provider = JellyfinProvider(
            server = server,
            apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger),
            mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger),
            credentialVault = credentialVault,
            logger = logger,
        )
        return ProviderHandle(
            provider = provider,
            auth = provider,
            library = provider,
            playback = provider,
            search = provider,
            subtitle = provider,
            progress = provider,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinProviderModule {
    @Binds
    @IntoSet
    abstract fun bindJellyfinProviderFactory(factory: JellyfinProviderFactory): MediaProviderFactory
}
