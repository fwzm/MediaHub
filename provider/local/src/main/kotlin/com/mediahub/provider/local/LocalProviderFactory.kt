package com.mediahub.provider.local

import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalProviderFactory @Inject constructor(
    private val rootProvider: LocalRootProvider,
    private val logger: Logger,
) : MediaProviderFactory {

    override val serverType: ServerType = ServerType.LOCAL

    override fun create(server: MediaServer): MediaProvider =
        LocalProvider(server, rootProvider, logger)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalProviderModule {
    @Binds
    @IntoSet
    abstract fun bindLocalProviderFactory(factory: LocalProviderFactory): MediaProviderFactory
}
