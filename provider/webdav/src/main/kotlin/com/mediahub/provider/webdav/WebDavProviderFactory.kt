package com.mediahub.provider.webdav

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
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
 * WebDAV Provider 工厂（只读能力包）。
 *
 * 装配的能力（ADR-022：Handle 只放**真正实现完成**的能力）：
 * AUTH + BROWSE + DETAIL + PLAYBACK + SUBTITLE_DISCOVERY。不含 SEARCH / LIBRARY / PROGRESS。
 *
 * 同一 handle 的能力共享凭据协调器；重建 handle 也复用应用级协调器，
 * 防止旧身份或迟到认证结果越过当前世代。网络 IO 不持有凭据锁。
 */
@Singleton
class WebDavProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
    private val tokenStore: TokenStore,
    private val credentialVault: CredentialVault,
    private val credentialCoordinator: WebDavCredentialCoordinator,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = WEBDAV_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger)
        val mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger)

        // 协调器是单例：每次 create 新建的 store 共享同一张按 server 隔离的世代表
        // （A2-1：跨 handle 的迟到失败保护依赖这一点）。
        val credentialStore = WebDavCredentialStore(credentialVault, credentialCoordinator)
        val session = WebDavSession(server, credentialStore)
        val api = WebDavApi(server.id, mediaHttpClient, logger)

        val provider = WebDavProvider(
            server = server,
            apiClient = apiClient,
            mediaHttpClient = mediaHttpClient,
            tokenStore = tokenStore,
            logger = logger,
            credentialVault = credentialVault,
            credentialCoordinator = credentialCoordinator,
        )
        return ProviderHandle(
            provider = provider,
            auth = WebDavAuthProvider(
                server = server,
                api = api,
                credentialStore = credentialStore,
                tokenStore = tokenStore,
                logger = logger,
            ),
            browse = WebDavBrowseProvider(
                server = server,
                api = api,
                session = session,
            ),
            detail = WebDavDetailProvider(
                server = server,
                api = api,
                session = session,
            ),
            playback = WebDavPlaybackProvider(
                server = server,
                session = session,
                logger = logger,
            ),
            subtitleDiscovery = WebDavSubtitleDiscoveryProvider(
                server = server,
                api = api,
                session = session,
            ),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavProviderModule {
    @Binds
    @IntoSet
    abstract fun bindWebDavProviderFactory(factory: WebDavProviderFactory): MediaProviderFactory

    @Binds
    @IntoSet
    abstract fun bindWebDavCredentialGenerationInvalidator(
        invalidator: WebDavCredentialGenerationInvalidator,
    ): com.mediahub.provider.api.CredentialGenerationInvalidator
}
