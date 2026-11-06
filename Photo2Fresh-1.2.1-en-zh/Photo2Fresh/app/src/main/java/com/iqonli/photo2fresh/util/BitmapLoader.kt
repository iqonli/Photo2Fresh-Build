package com.iqonli.photo2fresh.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build

/**
 * 只用于界面预览。正式流水线通过 ImageNormalizer 读取 EXIF 并把方向永久写入像素。
 */
fun ContentResolver.decodePreviewBitmap(uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(this, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longestSide = maxOf(width, height)
            if (longestSide > MAX_PREVIEW_SIDE_PX) {
                val scale = MAX_PREVIEW_SIDE_PX.toFloat() / longestSide.toFloat()
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } else {
        openInputStream(uri).use { stream ->
            requireNotNull(stream) { "无法打开所选图片" }
            val bitmap = BitmapFactory.decodeStream(stream)
            requireNotNull(bitmap) { "无法解码所选图片" }
            bitmap.scaleDownIfNeeded(MAX_PREVIEW_SIDE_PX)
        }
    }
}

private fun Bitmap.scaleDownIfNeeded(maxSidePx: Int): Bitmap {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxSidePx) return this

    val scale = maxSidePx.toFloat() / longestSide.toFloat()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private const val MAX_PREVIEW_SIDE_PX = 1600
