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
 * AUTH + BROWSE + DETAIL + PLAYBACK。不含 SEARCH / LIBRARY / PROGRESS。
 *
 * 与 Emby/Jellyfin 工厂的差异说明：main 上的 [TokenStore] 只有令牌存取，
 * 没有 PR #18 分支引入的身份世代 lease API；本工厂因此不装配身份守卫
 * （与 main 上的 Emby/Jellyfin 工厂一致），避免对未合并分支形成编译依赖。
 * 地址/身份变更时的旧凭据失效由 `ServerRepository` + Provider 重建负责。
 */
@Singleton
class WebDavProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
    private val tokenStore: TokenStore,
    private val credentialVault: CredentialVault,
    private val logger: Logger,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = WEBDAV_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val apiClient = ApiClient(httpClientFactory.apiClient(), logger = logger)
        val mediaHttpClient = MediaHttpClient(httpClientFactory.mediaClient(), logger = logger)

        val credentialStore = WebDavCredentialStore(credentialVault)
        val session = WebDavSession(server, credentialStore)
        val api = WebDavApi(server.id, mediaHttpClient, logger)

        val provider = WebDavProvider(
            server = server,
            apiClient = apiClient,
            mediaHttpClient = mediaHttpClient,
            tokenStore = tokenStore,
            logger = logger,
            credentialVault = credentialVault,
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
