package com.xinyu.companion.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

interface CompanionAvatarStorage {
    fun importAvatar(sourceUri: Uri): Result<String>

    fun clearAvatars()
}

class LocalCompanionAvatarStorage(
    private val context: Context,
) : CompanionAvatarStorage {
    private val avatarDir: File
        get() = File(context.filesDir, AVATAR_DIR_NAME).apply { mkdirs() }

    override fun importAvatar(sourceUri: Uri): Result<String> = runCatching {
        val decoded = decodeSampledBitmap(sourceUri)
            ?: throw IllegalArgumentException("无法读取所选图片，请换一张重试")
        val target = File(avatarDir, "companion_avatar_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(target).use { out ->
                val ok = decoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (!ok) throw IllegalStateException("无法保存头像图片")
            }
        } finally {
            decoded.recycle()
        }
        avatarDir.listFiles()
            ?.filter { it.absolutePath != target.absolutePath }
            ?.forEach { runCatching { it.delete() } }
        target.absolutePath
    }

    override fun clearAvatars() {
        avatarDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        val maxSide = maxOf(sampled.width, sampled.height)
        if (maxSide <= MAX_DIMENSION_PX) return sampled
        val scale = MAX_DIMENSION_PX.toFloat() / maxSide.toFloat()
        val width = (sampled.width * scale).toInt().coerceAtLeast(1)
        val height = (sampled.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(sampled, width, height, true)
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxDim && halfHeight / sample >= maxDim) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val AVATAR_DIR_NAME = "avatars"
        const val MAX_DIMENSION_PX = 512
        const val JPEG_QUALITY = 90
    }
}
