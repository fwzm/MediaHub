package com.mediahub.player.mpv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidContentByteSourceTest {
    class FixtureProvider : ContentProvider() {
        lateinit var file: File
        var queries = 0
        var knownLength = true
        var denied = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            queries++
            if (denied) throw SecurityException("Fixture grant revoked")
            return MatrixCursor(arrayOf(OpenableColumns.SIZE)).also {
                it.addRow(arrayOf(if (knownLength) 7L else null))
            }
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            if (denied) throw SecurityException("Fixture grant revoked")
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 5, 7)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    private lateinit var provider: FixtureProvider
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private val uri = Uri.parse("content://fixture.bytes/document/opaque-video")

    @Before fun before() {
        provider = FixtureProvider().also {
            it.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
                authority = "fixture.bytes"
                exported = true
            })
        }
        provider.file = File(RuntimeEnvironment.getApplication().cacheDir, "document-offset-fixture.bin")
            .also { it.writeText("HEAD!payloadTAIL") }
        ShadowContentResolver.registerProviderInternal("fixture.bytes", provider)
    }

    @Test fun anAssetDescriptorOffsetDoesNotLeakThePrefixOrTail() {
        AndroidContentByteSource(resolver, uri).use { source ->
            assertEquals(7L, source.info().length)
            assertEquals("video/mp4", source.info().mimeType)
            assertEquals("payload", source.open().use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }

    @Test fun unknownMetadataRemainsUnknownAndClosedSourcesNeverQueryAgain() {
        provider.knownLength = false
        val source = AndroidContentByteSource(resolver, uri)
        assertNull(source.info().length)
        source.close(); source.close()
        val before = provider.queries
        assertTrue(runCatching { source.info() }.isFailure)
        assertTrue(runCatching { source.open() }.isFailure)
        assertEquals(before, provider.queries)
    }

    @Test fun revokedGrantsFailWithoutAnyNetworkFallback() {
        provider.denied = true
        AndroidContentByteSource(resolver, uri).use { source ->
            assertEquals("Fixture grant revoked", runCatching { source.info() }.exceptionOrNull()?.message)
            assertEquals("Fixture grant revoked", runCatching { source.open() }.exceptionOrNull()?.message)
        }
    }
}
