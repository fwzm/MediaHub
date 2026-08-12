package com.mediahub.provider.local

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
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

@Singleton
class LocalProviderFactory @Inject constructor(
    private val rootProvider: LocalRootProvider,
) : MediaProviderFactory {

    override val descriptor: ProviderDescriptor = LOCAL_PROVIDER_DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val provider = LocalProvider(server, rootProvider)
        return ProviderHandle(
            provider = provider,
            browse = provider,
            detail = provider,
            playback = provider,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalProviderModule {
    @Binds
    @IntoSet
    abstract fun bindLocalProviderFactory(factory: LocalProviderFactory): MediaProviderFactory
}
