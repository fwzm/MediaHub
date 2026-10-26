package com.mediahub.player.mpv

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.mediahub.core.network.ContentByteSource
import java.io.IOException
import java.io.InputStream

/** One explicitly selected document. All resolver calls are made by bridge workers. */
internal class AndroidContentByteSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ContentByteSource {
    private val lock = Any()
    private var closed = false
    private val pending = mutableSetOf<CancellationSignal>()

    init { require(uri.scheme.equals("content", ignoreCase = true) && !uri.authority.isNullOrBlank()) }

    private fun <T> resolve(block: (CancellationSignal) -> T): T {
        val signal = CancellationSignal()
        synchronized(lock) {
            if (closed) throw IOException("Document session closed")
            pending += signal
        }
        try { return block(signal) }
        finally { synchronized(lock) { pending -= signal } }
    }

    override fun info(): ContentByteSource.Info = resolve { signal ->
        val length = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null, signal)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index).takeIf { it >= 0 }
            else null
        }
        ContentByteSource.Info(length, resolver.getType(uri))
    }

    override fun open(): InputStream = resolve { signal ->
        val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal)
            ?: throw IOException("Document unavailable")
        try {
            // createInputStream respects the descriptor's offset and declared length.
            val input = descriptor.createInputStream()
            synchronized(lock) {
                if (closed) {
                    runCatching { input.close() }
                    throw IOException("Document session closed")
                }
            }
            input
        } catch (failure: Exception) {
            runCatching { descriptor.close() }
            throw failure
        }
    }

    override fun close() {
        val signals = synchronized(lock) {
            if (closed) return
            closed = true
            pending.toList()
        }
        signals.forEach { it.cancel() }
    }
}
