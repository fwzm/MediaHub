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
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.KeystoreSecretStorage
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.player.engine.MediaCacheProvider
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEngineFactory
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
    fun provideHttpClientFactory(logger: Logger): HttpClientFactory = HttpClientFactory(logger)

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
            .addMigrations(Migrations.MIGRATION_1_2)
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
    ): PlayerFactory = PlayerFactory(context, mediaCacheProvider)

    @Provides
    @Singleton
    fun providePlaybackEngineFactory(
        playerFactory: PlayerFactory,
        logger: Logger,
    ): PlaybackEngineCreator = PlaybackEngineFactory(playerFactory, logger)

    @Provides
    @Singleton
    fun provideLocalRootProvider(@ApplicationContext context: Context): LocalRootProvider =
        AppLocalRootProvider(context)
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
    abstract fun bindProgressStore(impl: ProgressRepository): ProgressStore

    @Binds
    @Singleton
    abstract fun bindUserPreferences(impl: UserPreferencesStore): UserPreferencesRepository
}
