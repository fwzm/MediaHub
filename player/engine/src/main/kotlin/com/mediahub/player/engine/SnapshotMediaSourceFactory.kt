package com.mediahub.player.engine

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier

/**
 * Capture a private header wrapper at MediaSource creation, before any delayed IO.
 * DefaultMediaSourceFactory clears its cached format factories when the data-source
 * factory changes; already-created sources retain their original factory.
 */
@OptIn(UnstableApi::class)
internal class SnapshotMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
    private val dataSources: () -> DataSource.Factory,
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource = synchronized(delegate) {
        delegate.setDataSourceFactory(dataSources()).createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = synchronized(delegate) { delegate.supportedTypes }

    // Return this wrapper from every configuration method. Returning the delegate
    // would let a chained call bypass the snapshot boundary.
    private fun configure(block: () -> Unit): MediaSource.Factory = apply {
        synchronized(delegate) { block() }
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        configure { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        configure { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory): MediaSource.Factory =
        configure { delegate.setCmcdConfigurationFactory(factory) }

    override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean): MediaSource.Factory =
        configure { delegate.experimentalParseSubtitlesDuringExtraction(parse) }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): MediaSource.Factory =
        configure { delegate.setSubtitleParserFactory(factory) }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): MediaSource.Factory =
        configure { delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs) }

    override fun setDownloadExecutor(executor: Supplier<ReleasableExecutor>): MediaSource.Factory =
        configure { delegate.setDownloadExecutor(executor) }
}
