package com.mediahub.feature.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mediahub.model.SubtitleFormats
import com.mediahub.provider.api.DiscoveredSubtitle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reconstruct only a previously granted local document; no network lookup or schema change. */
open class ImportedSubtitleResolver @Inject constructor(@param:ApplicationContext private val context: Context) {
    open suspend fun resolve(id: String): DiscoveredSubtitle? = withContext(Dispatchers.IO) {
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
}
