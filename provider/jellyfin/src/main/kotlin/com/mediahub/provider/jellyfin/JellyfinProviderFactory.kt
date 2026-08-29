package com.mediahub.provider.jellyfin

import com.mediahub.core.common.ClientIdentity
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
import com.mediahub.provider.api.ProviderImageAuthContributor
import com.mediahub.provider.api.ProviderSessionCleaner
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinEndpointResolver
import com.mediahub.provider.jellyfin.auth.JellyfinAuthProvider
import com.mediahub.provider.jellyfin.detail.JellyfinDetailProvider
import com.mediahub.provider.jellyfin.image.JellyfinImageAuthContributor
import com.mediahub.provider.jellyfin.library.JellyfinLibraryProvider
import com.mediahub.provider.jellyfin.playback.JellyfinPlaybackProvider
import com.mediahub.provider.jellyfin.progress.JellyfinProgressProvider
import com.mediahub.provider.jellyfin.search.JellyfinSearchProvider
import com.mediahub.provider.jellyfin.session.JellyfinSessionCleaner
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
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
    private val clientIdentity: ClientIdentity,
    private val jellyfinSessionStorage: JellyfinSessionStore.Storage,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = JELLYFIN_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(clientIdentity)
        val apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger)
        val mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger)
        val endpointResolver = JellyfinEndpointResolver(server.baseUrl)
        val jellyfinApi = JellyfinApiClient(endpointResolver, apiClient, authHeaderBuilder, logger)
        val sessionStore = JellyfinSessionStore(jellyfinSessionStorage)

        val provider = JellyfinProvider(
            server = server,
            apiClient = apiClient,
            mediaHttpClient = mediaHttpClient,
            tokenStore = tokenStore,
            logger = logger,
            jellyfinApi = jellyfinApi,
            authHeaderBuilder = authHeaderBuilder,
        )
        val authProvider = JellyfinAuthProvider(
            server = server,
            api = jellyfinApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val libraryProvider = JellyfinLibraryProvider(
            server = server,
            api = jellyfinApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val detailProvider = JellyfinDetailProvider(
            server = server,
            api = jellyfinApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val search = JellyfinSearchProvider(
            server = server,
            api = jellyfinApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val playbackProvider = JellyfinPlaybackProvider(
            server = server,
            api = jellyfinApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val progressProvider = JellyfinProgressProvider(
            server = server,
            api = jellyfinApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        // ADR-022/039：Handle 只暴露"当前版本真正实现完成"的能力——
        // Phase 1G-C 开放 AUTH + LIBRARY + DETAIL + SEARCH + PLAYBACK + PROGRESS；
        // QUERY 待后续 slice；**IDENTITY_LOOKUP 因 Jellyfin 无按值 ProviderId 查询协议
        // 保持 null（DEFER）**——DetailViewModel 的 eligibility guard 据此阻止单向切源。
        return ProviderHandle(
            provider = provider,
            auth = authProvider,
            library = libraryProvider,
            detail = detailProvider,
            search = search,
            playback = playbackProvider,
            progress = progressProvider,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinProviderModule {
    @Binds
    @IntoSet
    abstract fun bindJellyfinProviderFactory(factory: JellyfinProviderFactory): MediaProviderFactory

    @Binds
    @IntoSet
    abstract fun bindJellyfinImageAuthContributor(impl: JellyfinImageAuthContributor): ProviderImageAuthContributor

    @Binds
    @IntoSet
    abstract fun bindJellyfinSessionCleaner(impl: JellyfinSessionCleaner): ProviderSessionCleaner
}
