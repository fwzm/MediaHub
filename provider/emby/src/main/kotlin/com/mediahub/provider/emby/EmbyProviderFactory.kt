package com.mediahub.provider.emby

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
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.detail.EmbyDetailProvider
import com.mediahub.provider.emby.library.EmbyLibraryProvider
import com.mediahub.provider.emby.playback.EmbyPlaybackProvider
import com.mediahub.provider.emby.search.EmbySearchProvider
import com.mediahub.provider.emby.identity.EmbyIdentityLookupProvider
import com.mediahub.provider.emby.session.EmbySessionStore
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
    private val clientIdentity: ClientIdentity,
    private val sessionStoreStorage: EmbySessionStore.Storage,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = EMBY_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val authHeaderBuilder = EmbyAuthorizationHeaderBuilder(clientIdentity)
        val apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger)
        val mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger)
        val endpointResolver = EmbyEndpointResolver(server.baseUrl)

        val provider = EmbyProvider(
            server = server,
            apiClient = apiClient,
            mediaHttpClient = mediaHttpClient,
            tokenStore = tokenStore,
            logger = logger,
            authHeaderBuilder = authHeaderBuilder,
            endpointResolver = endpointResolver,
        )
        val embyApi = EmbyApiClient(endpointResolver, apiClient, authHeaderBuilder, logger)
        val sessionStore = EmbySessionStore(sessionStoreStorage)
        val authProvider = EmbyAuthProvider(
            server = server,
            api = embyApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val libraryProvider = EmbyLibraryProvider(
            server = server,
            api = embyApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val detailProvider = EmbyDetailProvider(
            server = server,
            api = embyApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        val playbackProvider = EmbyPlaybackProvider(
            server = server,
            api = embyApi,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
        // ADR-022/026：Handle 只暴露当前已实现能力——Phase 1F 开放
        // AUTH + LIBRARY + DETAIL + PLAYBACK + QUERY（服务端排序查询管道，1C-2）
        // + SEARCH（SearchTerm 全库搜索，1C-1）
        // + IDENTITY_LOOKUP（AnyProviderIdEquals 精确身份查找，1F-B1/ADR-038）。
        // 同一 EmbyLibraryProvider 实例承载 LIBRARY 与 QUERY 两个能力接口。
        return ProviderHandle(
            provider = provider,
            auth = authProvider,
            library = libraryProvider,
            detail = detailProvider,
            playback = playbackProvider,
            query = libraryProvider,
            search = EmbySearchProvider(
                server = server,
                api = embyApi,
                tokenStore = tokenStore,
                sessionStore = sessionStore,
                logger = logger,
            ),
            identityLookup = EmbyIdentityLookupProvider(
                server = server,
                api = embyApi,
                tokenStore = tokenStore,
                sessionStore = sessionStore,
                logger = logger,
            ),
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
