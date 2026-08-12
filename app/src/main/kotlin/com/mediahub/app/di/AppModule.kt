package com.mediahub.app.di

import android.content.Context
import androidx.room.Room
import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.logging.CompositeLogger
import com.mediahub.core.logging.LogBuffer
import com.mediahub.core.logging.LogcatLogger
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.MemoryLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.KeystoreSecretStorage
import com.mediahub.core.security.SecretStorage
import com.mediahub.player.engine.MediaCacheProvider
import com.mediahub.player.engine.PlaybackEngineFactory
import com.mediahub.player.engine.PlayerFactory
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.base.DefaultProviderRegistry
import com.mediahub.provider.base.EncryptedCredentialVault
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
    fun provideCredentialVault(storage: SecretStorage): CredentialVault =
        EncryptedCredentialVault(storage)

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
    ): PlaybackEngineFactory = PlaybackEngineFactory(playerFactory, logger)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RegistryModule {

    @Binds
    @Singleton
    abstract fun bindMediaProviderRegistry(impl: DefaultProviderRegistry): MediaProviderRegistry
}
