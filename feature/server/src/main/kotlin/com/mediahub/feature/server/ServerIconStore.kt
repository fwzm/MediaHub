package com.mediahub.feature.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 服务器自定义图标存储（Server Editor）。
 *
 * 把 Photo Picker 选中的图片缩放/中心裁剪为方形并复制到应用私有目录
 * files/server_icons/{serverId}.webp，返回 file:// 引用写入 MediaServer.icon。
 * 避免保存 SAF 的临时 content:// URI（权限过期/原图被删导致图标失效）。
 */
@Singleton
class ServerIconStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "server_icons")

    fun iconFile(serverId: String): File = File(dir, "$serverId.webp")

    fun iconReference(serverId: String): String = "file://" + iconFile(serverId).absolutePath

    /** 从 content:// URI 缩放/裁剪并写盘；返回 file:// 引用。 */
    suspend fun saveFromUri(serverId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val square = decodeSquare(uri, TARGET_SIZE) ?: error("无法读取所选图片")
        FileOutputStream(iconFile(serverId)).use { out ->
            square.compress(Bitmap.CompressFormat.WEBP, 90, out)
        }
        square.recycle()
        iconReference(serverId)
    }

    suspend fun remove(serverId: String) = withContext(Dispatchers.IO) {
        iconFile(serverId).delete()
    }

    private fun decodeSquare(uri: Uri, targetSize: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (w / sample > targetSize * 2 || h / sample > targetSize * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val size = minOf(decoded.width, decoded.height)
        val crop = Bitmap.createBitmap(
            decoded,
            (decoded.width - size) / 2,
            (decoded.height - size) / 2,
            size,
            size,
        )
        if (crop !== decoded) decoded.recycle()

        val scaled = if (crop.width == targetSize && crop.height == targetSize) {
            crop
        } else {
            Bitmap.createScaledBitmap(crop, targetSize, targetSize, true)
        }
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    private companion object {
        const val TARGET_SIZE = 512
    }
}
