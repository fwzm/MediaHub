package com.mediahub.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mediahub.core.ui.effects.ArtworkPalette
import com.mediahub.core.ui.effects.VisualPalette
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads artwork through MediaHub's application ImageLoader (therefore preserving the existing
 * origin-scoped image authentication policy), extracts a small palette off the main thread, and
 * caches by stable media/artwork key. Failures are deliberately nullable and never block playback.
 */
@Singleton
class ArtworkPaletteController @Inject constructor(
    @ApplicationContext context: Context,
) : ArtworkPaletteLoader {
    private val appContext = context.applicationContext
    private val imageLoader = appContext.imageLoader
    private val coordinator = ArtworkPaletteLoadCoordinator(CACHE_SIZE) { url ->
        withContext(Dispatchers.IO) { loadAndExtract(url) }
    }

    override suspend fun load(artworkKey: String, url: String?): VisualPalette? {
        return coordinator.load(artworkKey, url)
    }

    @WorkerThread
    private suspend fun loadAndExtract(url: String): VisualPalette? {
        val request = ImageRequest.Builder(appContext)
            .data(url)
            .allowHardware(false)
            .size(ARTWORK_DECODE_SIZE)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        val drawable = result.drawable
        val bitmapDrawable = drawable as? BitmapDrawable
        val bitmap = bitmapDrawable?.bitmap ?: run {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ARTWORK_DECODE_SIZE
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ARTWORK_DECODE_SIZE
            createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                drawable.setBounds(0, 0, target.width, target.height)
                drawable.draw(canvas)
            }
        }
        return try {
            ArtworkPalette.fromBitmap(bitmap)
        } finally {
            if (bitmapDrawable == null) bitmap.recycle()
        }
    }

    internal fun cached(artworkKey: String): VisualPalette? = coordinator.cached(artworkKey)

    private companion object {
        const val ARTWORK_DECODE_SIZE = 128
        const val CACHE_SIZE = 24
    }
}

/**
 * Pure cache/in-flight coordinator. It makes equal artwork requests share one extraction, keeps
 * failures out of the LRU, and propagates cancellation instead of converting it into a fake miss.
 */
internal class ArtworkPaletteLoadCoordinator(
    private val maxEntries: Int = 24,
    private val extractor: suspend (String) -> VisualPalette?,
) {
    init {
        require(maxEntries > 0)
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, VisualPalette>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VisualPalette>?): Boolean =
            size > maxEntries
    }
    private val inFlight = mutableMapOf<String, CompletableDeferred<VisualPalette?>>()

    suspend fun load(artworkKey: String, url: String?): VisualPalette? {
        if (artworkKey.isBlank() || url.isNullOrBlank()) return null

        var ownsLoad = false
        val pending = synchronized(lock) {
            cache[artworkKey]?.let { return it }
            inFlight[artworkKey] ?: CompletableDeferred<VisualPalette?>().also {
                inFlight[artworkKey] = it
                ownsLoad = true
            }
        }
        if (!ownsLoad) return pending.await()

        return try {
            val extracted = try {
                extractor(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            synchronized(lock) {
                if (extracted != null) cache[artworkKey] = extracted
                inFlight.remove(artworkKey)
            }
            pending.complete(extracted)
            extracted
        } catch (cancelled: CancellationException) {
            synchronized(lock) { inFlight.remove(artworkKey) }
            pending.completeExceptionally(cancelled)
            throw cancelled
        }
    }

    fun cached(artworkKey: String): VisualPalette? = synchronized(lock) { cache[artworkKey] }
}

/** Testable async boundary consumed by PlayerViewModel. */
fun interface ArtworkPaletteLoader {
    suspend fun load(artworkKey: String, url: String?): VisualPalette?
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ArtworkPaletteModule {
    @Binds
    @Singleton
    abstract fun bindArtworkPaletteLoader(
        implementation: ArtworkPaletteController,
    ): ArtworkPaletteLoader
}
