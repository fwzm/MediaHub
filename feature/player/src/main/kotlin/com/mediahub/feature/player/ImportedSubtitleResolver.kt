package com.mediahub.feature.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mediahub.model.SubtitleFormats
import com.mediahub.provider.api.DiscoveredSubtitle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reconstruct only a previously granted local document; no network lookup or schema change. */
open class ImportedSubtitleResolver @Inject constructor(@param:ApplicationContext private val context: Context) {
    /** 读取/转码用的调度器；测试可替换为同一 TestDispatcher 以确定性完成。 */
    protected open val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

    open suspend fun resolve(id: String): DiscoveredSubtitle? = withContext(ioDispatcher) {
        val uri = Uri.parse(id)
        if (uri.scheme != "content") return@withContext null
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return@withContext null
        try {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            } ?: return@withContext null
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in SubtitleFormats.EXTENSIONS) return@withContext null
            DiscoveredSubtitle(id = id, name = name.substringBeforeLast('.'), fileName = name,
                extension = extension, language = SubtitleFormats.languageFromFileName(name), uri = id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // Lost access/metadata is an honest unavailable state, never a success memory.
        }
    }

    /** 本地/SAF 字幕在交给内核前转成 UTF-8；无法读取或无需转换时原样返回。 */
    open suspend fun prepare(subtitle: DiscoveredSubtitle): DiscoveredSubtitle = withContext(ioDispatcher) {
        val parsed = runCatching { Uri.parse(subtitle.uri) }.getOrNull() ?: return@withContext subtitle
        val bytes = try {
            when (parsed.scheme?.lowercase()) {
                "content" -> context.contentResolver.openInputStream(parsed)?.use(::readLimited)
                "file" -> parsed.path?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use(::readLimited)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext subtitle
        val cached = SubtitleTextCache(context.cacheDir).prepare(bytes, subtitle.extension)
            ?: return@withContext subtitle
        subtitle.copy(uri = Uri.fromFile(cached).toString())
    }

    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > SubtitleTextCache.MAX_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
