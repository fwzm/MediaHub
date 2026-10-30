package com.mediahub.app.di

import com.mediahub.app.BuildConfig

import android.content.Context
import androidx.room.Room
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.common.ClientIdentityProvider
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.Migrations
import com.mediahub.core.logging.CompositeLogger
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.LogcatLogger
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.MemoryLogger
import com.mediahub.feature.settings.DiagnosticsExportPort
import com.mediahub.feature.settings.FileDiagnosticsExportPort
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.KeystoreSecretStorage
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.player.engine.EnginePreferenceHistory
import com.mediahub.player.engine.MediaCacheProvider
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEngineFactory
import com.mediahub.player.mpv.MpvPlaybackEngineCreator
import com.mediahub.player.engine.PlayerFactory
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.base.DefaultProviderRegistry
import com.mediahub.provider.emby.session.EmbySessionStore
import com.mediahub.provider.api.SessionStoreCleaner
import com.mediahub.provider.local.LocalRootProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLogBuffer(): LogBuffer = LogBuffer()

    @Provides
    @Singleton
    fun provideLogger(logBuffer: LogBuffer): Logger =
        CompositeLogger(listOf(LogcatLogger(), MemoryLogger(logBuffer)))

    @Provides
    @Singleton
    fun provideDiagnosticsExportPort(
        @ApplicationContext context: Context,
        logBuffer: LogBuffer,
    ): DiagnosticsExportPort = FileDiagnosticsExportPort(
        buffer = logBuffer,
        directory = context.filesDir.resolve("diagnostics"),
        appVersion = BuildConfig.VERSION_NAME,
        device = android.os.Build.MODEL,
    )

    @Provides
    @Singleton
    fun provideAppDispatchers(): AppDispatchers = AppDispatchers()

    @Provides
    @Singleton
    fun provideSecretStorage(
        @ApplicationContext context: Context,
        logger: Logger,
    ): SecretStorage = KeystoreSecretStorage(context, logger)

    @Provides
    @Singleton
    fun provideTokenStore(storage: SecretStorage): TokenStore = TokenStore(storage)

    @Provides
    @Singleton
    fun provideCredentialVault(storage: SecretStorage): CredentialVault = CredentialVault(storage)

    @Provides
    @Singleton
    fun provideClientIdentity(
        @ApplicationContext context: Context,
    ): ClientIdentity = ClientIdentityProvider(context, version = BuildConfig.VERSION_NAME).get()

    @Provides
    @Singleton
    fun provideEmbySessionStorage(
        @ApplicationContext context: Context,
    ): EmbySessionStore.Storage = EmbySessionStore.SharedPrefsStorage(context)

    @Provides
    @Singleton
    fun provideJellyfinSessionStorage(
        @ApplicationContext context: Context,
    ): com.mediahub.provider.jellyfin.session.JellyfinSessionStore.Storage =
        com.mediahub.provider.jellyfin.session.JellyfinSessionStore.SharedPrefsStorage(context)

    /**
     * ADR-039：删除媒体源 = 组合清理全部 Provider 会话元数据。
     * Cleaner 经 `@IntoSet`（ProviderSessionCleaner）由各 Provider 模块自行注册——
     * app composition root 不枚举具体 Provider，第三个带会话的 Provider 零改动接入。
     */
    @Provides
    @Singleton
    fun provideSessionStoreCleaner(
        cleaners: Set<@JvmSuppressWildcards com.mediahub.provider.api.ProviderSessionCleaner>,
    ): SessionStoreCleaner = com.mediahub.provider.api.CompositeSessionStoreCleaner(cleaners)

    /**
     * M0-A（ADR-045）+ M0-A3 真机缺陷修复：URL -> serverId 解析。
     *
     * 先用**覆盖所有服务器类型**的 [ServerUrlIndex]（WebDAV / LOCAL 没有图片鉴权作用域，
     * 旧实现会解析不到 serverId → 自定义 UA 不生效，真机实测复现）；
     * 再回落到图片鉴权作用域，保持与凭据归属一致的最长前缀 + fail-closed 语义。
     */
    @Provides
    @Singleton
    fun provideServerIdResolver(
        urlIndex: com.mediahub.app.net.ServerUrlIndex,
        store: com.mediahub.app.image.ProviderImageAuthStore,
    ): com.mediahub.core.network.ServerIdResolver =
        com.mediahub.core.network.ServerIdResolver { url ->
            val lookup = urlIndex.lookupAfterStartup(url)
            // A matching ambiguous scope and an unavailable primary index are fail-closed.
            // Only a ready index with no match may use a provider-derived image-auth scope.
            if (!lookup.ready || lookup.matched) lookup.serverId else store.serverIdForUrl(url)
        }

    /** M0-A 闭环：接口 → DataStore 实现的绑定（实现类自身可注入，此处按接口暴露）。 */
    @Provides
    @Singleton
    fun provideServerUserAgentRepository(
        impl: com.mediahub.core.database.repository.DataStoreServerUserAgentRepository,
    ): com.mediahub.core.database.repository.ServerUserAgentRepository = impl

    /**
     * M0-A（ADR-045）：全局 UA 策略。自定义覆盖来自 [ServerUserAgentRepository] 的
     * **同步快照**（拦截器运行在请求线程上，不能 suspend）；未配置 / 非法值一律回落默认 UA。
     */
    @Provides
    @Singleton
    fun provideUserAgentPolicy(
        serverUserAgent: com.mediahub.core.database.repository.ServerUserAgentRepository,
    ): com.mediahub.core.network.UserAgentPolicy =
        com.mediahub.core.network.UserAgentPolicy(
            // 首次加载未完成时做**有界等待**（只等信号，拦截器线程不做 DataStore I/O），
            // 避免在已保存配置尚未读出时静默发出与配置不一致的请求。
            overrides = { serverUserAgent.snapshotBlocking() },
            // 默认 UA 使用真实应用版本，不再硬编码 MediaHub/1.0。
            defaultUserAgent = com.mediahub.core.network.MediaHubUserAgent.ofVersion(
                com.mediahub.app.BuildConfig.VERSION_NAME,
                android.os.Build.VERSION.RELEASE,
            ),
        )

    @Provides
    @Singleton
    fun provideHttpClientFactory(
        logger: Logger,
        userAgentPolicy: com.mediahub.core.network.UserAgentPolicy,
        serverIdFor: com.mediahub.core.network.ServerIdResolver,
    ): HttpClientFactory = HttpClientFactory(logger, userAgentPolicy, serverIdFor)

    /**
     * M16-G4：网络交换观测台账（进程内单例）。
     *
     * 设计约束（见 AGENTS.md 与 docs/reviews/m16-s0-metric-definition.md）：
     * - **纯内存**、有界聚合，不持久化任何请求级细节；
     * - 不携带 URL / path / query / token / cookie / Authorization / UA / 服务器名；
     * - 观测是**可选**的：未被注入的调用方保持"不观测"的旧行为。
     *
     * 单例是必需的 —— Media3、Coil、mpv 桥必须上报到**同一张**台账，
     * 否则 partialObservability 与计数会分散在多个实例里而无法汇总。
     */
    @Provides
    @Singleton
    fun provideNetworkExchangeLedger(): com.mediahub.core.network.NetworkExchangeLedger =
        com.mediahub.core.network.NetworkExchangeLedger()

    @Provides
    @Singleton
    fun provideEndpointTestService(factory: HttpClientFactory): com.mediahub.core.network.EndpointTestService =
        com.mediahub.core.network.EndpointTestService(factory)

    @Provides
    @Singleton
    fun provideApiClient(
        factory: HttpClientFactory,
        logger: Logger,
    ): ApiClient = ApiClient(factory.apiClient(), logger = logger)

    @Provides
    @Singleton
    fun provideMediaHttpClient(
        factory: HttpClientFactory,
        logger: Logger,
    ): MediaHttpClient = MediaHttpClient(factory.mediaClient(), logger)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(
                Migrations.MIGRATION_1_2,
                Migrations.MIGRATION_2_3,
                Migrations.MIGRATION_3_4,
                Migrations.MIGRATION_4_5,
            )
            .build()

    @Provides
    @Singleton
    fun provideMediaCacheProvider(@ApplicationContext context: Context): MediaCacheProvider =
        MediaCacheProvider(context)

    @Provides
    @Singleton
    fun providePlayerFactory(
        @ApplicationContext context: Context,
        mediaCacheProvider: MediaCacheProvider,
        userAgentPolicy: com.mediahub.core.network.UserAgentPolicy,
        serverIdFor: com.mediahub.core.network.ServerIdResolver,
        // M16/G4：Media3 取流路径的观测入口。**必须注入**，否则
        // PlayerFactory.mediaDataSourceFactory 永远不会调用 recordUnobservable(MEDIA)，
        // 台账会把"真实出网"表述成"零交换"（AGENTS.md §7.4 的伪造 0）。
        networkLedger: com.mediahub.core.network.NetworkExchangeLedger,
    ): PlayerFactory = PlayerFactory(
        context,
        mediaCacheProvider,
        userAgentPolicy,
        serverIdFor,
        ledger = networkLedger,
    )

    // U3-A：双内核工厂（Media3 快速路径 / mpv 兼容兜底），由 SwitchablePlaybackEngine
    // 按PlaybackEngineSelector + 失败指纹决定实际使用哪个；用户可在设置页强制指定。
    @Provides
    @Singleton
    @com.mediahub.player.engine.Media3EngineCreator
    fun provideMedia3EngineCreator(
        playerFactory: PlayerFactory,
        logger: Logger,
    ): PlaybackEngineCreator = PlaybackEngineFactory(playerFactory, logger)

    @Provides
    @Singleton
    @com.mediahub.player.engine.MpvEngineCreator
    fun provideMpvEngineCreator(
        @ApplicationContext context: Context,
        logger: Logger,
        httpClientFactory: HttpClientFactory,
        // M5-1 联合集成：复用 ADR-042 的**单一** DeviceCapabilitiesProvider 单例。
        // 集成前 M1/M2 各自创建了一个采集器（两个 MediaCodecList 枚举 + 两个预热线程），
        // 现统一为同一实例：线程安全（实现内部 synchronized 缓存）、进程内有效，
        // 且不携带任何服务器/会话可变状态，因此不存在跨服务器或跨会话的状态污染。
        deviceCapabilities: com.mediahub.player.compatibility.DeviceCapabilitiesProvider,
        // M16/G4：mpv 回环接入 / 本地文件读取的观测台账（同上，必须注入）。
        networkLedger: com.mediahub.core.network.NetworkExchangeLedger,
    ): PlaybackEngineCreator {
        return MpvPlaybackEngineCreator(
            context,
            logger,
            httpClientFactory,
            com.mediahub.player.mpv.DeviceHdrEvidenceProvider { hdr ->
                if (hdr == com.mediahub.model.HdrType.NONE) {
                    true
                } else {
                    val caps = runCatching { deviceCapabilities.get() }.getOrNull()
                    if (caps == null || caps.hdrSupported.isEmpty()) null else hdr in caps.hdrSupported
                }
            },
            networkLedger,
        )
    }

    /**
     * 设备能力采集（ADR-042）：结果在实现内部缓存（进程内不变）。
     * 首次枚举 MediaCodecList / Display 需要数十毫秒，这里在后台预热，
     * 保证起播同步路径只读缓存（不增加正常视频起播延迟）。
     */
    @Provides
    @Singleton
    fun provideDeviceCapabilitiesProvider(
        @ApplicationContext context: Context,
    ): com.mediahub.player.compatibility.DeviceCapabilitiesProvider {
        val provider = com.mediahub.player.compatibility.AndroidDeviceCapabilitiesProvider(context)
        Thread {
            runCatching { provider.get() }
        }.apply {
            isDaemon = true
            name = "device-capabilities-warmup"
        }.start()
        return provider
    }

    @Provides
    @Singleton
    fun provideLocalRootProvider(@ApplicationContext context: Context): LocalRootProvider =
        AppLocalRootProvider(context)

    /** 聚合搜索引擎（Phase 1C-1）：并发 4 / 单服超时 8s。 */
    @Provides
    @Singleton
    fun provideGlobalSearchEngine(): com.mediahub.feature.search.engine.GlobalSearchEngine =
        com.mediahub.feature.search.engine.GlobalSearchEngine(perServerTimeoutMs = 8_000, maxConcurrency = 4)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RegistryModule {

    @Binds
    @Singleton
    abstract fun bindMediaProviderRegistry(impl: DefaultProviderRegistry): MediaProviderRegistry

    @Binds
    @Singleton
    abstract fun bindServerStore(impl: ServerRepository): ServerStore

    @Binds
    @Singleton
    abstract fun bindServerRemoveHandler(impl: com.mediahub.feature.server.RemoveServerUseCase): com.mediahub.feature.server.ServerRemoveHandler

    @Binds
    @Singleton
    abstract fun bindProgressStore(impl: ProgressRepository): ProgressStore

    @Binds
    @Singleton
    abstract fun bindSubtitleMemoryStore(impl: com.mediahub.core.database.repository.SubtitleMemoryRepository): com.mediahub.core.database.repository.SubtitleMemoryStore

    @Binds
    @Singleton
    abstract fun bindUserPreferences(impl: UserPreferencesStore): UserPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindEnginePreferenceHistory(impl: DataStoreEnginePreferenceHistory): EnginePreferenceHistory

    @Binds
    @Singleton
    abstract fun bindBackupDataSource(impl: com.mediahub.feature.settings.backup.ProductionBackupDataSource): com.mediahub.feature.settings.backup.BackupDataSource

    @Binds
    @Singleton
    abstract fun bindRestoreSnapshotStorage(impl: com.mediahub.feature.settings.backup.RestoreSnapshotStore): com.mediahub.feature.settings.backup.RestoreSnapshotStorage

    @Binds
    @Singleton
    abstract fun bindRestoreJournal(impl: com.mediahub.feature.settings.backup.SharedPrefsRestoreJournal): com.mediahub.feature.settings.backup.RestoreJournal

    @Binds
    @Singleton
    abstract fun bindRestoreLoginInvalidator(impl: com.mediahub.feature.settings.backup.TokenSessionLoginInvalidator): com.mediahub.feature.settings.backup.RestoreLoginInvalidator

    @Binds
    @Singleton
    abstract fun bindBackupFileStore(impl: com.mediahub.feature.settings.backup.ProductionBackupFileStore): com.mediahub.feature.settings.backup.BackupFileStore
}
