package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 为播放 DataSource 注入每次播放的请求头（鉴权 / Cookie / Referer 等）。
 * 包装在 CacheDataSource 外层；每个实例捕获独立、不可变的 [PlaybackRequestContext]。
 */
@OptIn(UnstableApi::class)
class HeaderAwareDataSourceFactory(
    private val base: DataSource.Factory,
    private val requestContext: PlaybackRequestContext,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        HeaderAwareDataSource(base.createDataSource(), requestContext)
}

@OptIn(UnstableApi::class)
class HeaderAwareDataSource(
    private val delegate: DataSource,
    private val requestContext: PlaybackRequestContext,
) : DataSource {

    override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)

    override fun open(dataSpec: DataSpec): Long {
        val headers = requestContext.headers
        val spec = if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers)
        return delegate.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri(): Uri = delegate.uri ?: Uri.EMPTY

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        delegate.close()
    }
}
