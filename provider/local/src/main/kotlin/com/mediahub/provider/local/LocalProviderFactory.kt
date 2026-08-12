package com.mediahub.provider.local

import android.content.Context
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.ProviderHandle
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalProviderFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaProviderFactory {
    override val descriptor = LocalProvider.DESCRIPTOR

    override fun create(server: MediaServer): ProviderHandle {
        val provider = LocalProvider(server, context)
        return ProviderHandle(provider = provider, browse = provider, playback = provider)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalProviderModule {
    @Binds
    @IntoSet
    abstract fun bindLocalProviderFactory(factory: LocalProviderFactory): MediaProviderFactory
}
