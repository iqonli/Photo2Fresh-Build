package com.iqonli.photo2fresh.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow

object ImageNormalizer {
    fun prepare(context: Context, uri: Uri): PreparedImage {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "无法读取图片 EXIF" }
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "无法打开所选图片" }
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片尺寸" }

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_SIDE)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inScaled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val decoded = resolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "无法再次打开所选图片" }
            BitmapFactory.decodeStream(stream, null, options)
        }
        requireNotNull(decoded) { "无法解码图片；请改用 JPEG、PNG、WebP 或 HEIF" }

        val normalized = applyExifOrientation(decoded, orientation)
        if (normalized !== decoded) decoded.recycle()

        val outputDir = File(context.cacheDir, "normalized").apply { mkdirs() }
        val output = File(outputDir, "normalized_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { stream ->
            check(normalized.compress(Bitmap.CompressFormat.JPEG, NORMALIZED_JPEG_QUALITY, stream)) {
                "无法保存方向归一化图片"
            }
        }
        val normalizedUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            output,
        )

        return PreparedImage(
            originalUri = uri.toString(),
            normalizedUri = normalizedUri.toString(),
            width = normalized.width,
            height = normalized.height,
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            exifOrientation = orientation,
            decodeSampleSize = sampleSize,
        ).also {
            normalized.recycle()
            cleanupOldFiles(outputDir, keep = output)
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return 1
        val raw = longest.toDouble() / maxSide.toDouble()
        return 2.0.pow(ceil(log2(raw))).toInt().coerceAtLeast(1)
    }

    private fun cleanupOldFiles(directory: File, keep: File) {
        directory.listFiles()
            ?.filter { it != keep }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_CACHED_NORMALIZED_IMAGES - 1)
            ?.forEach(File::delete)
    }

    private const val MAX_DECODE_SIDE = 3000
    private const val NORMALIZED_JPEG_QUALITY = 95
    private const val MAX_CACHED_NORMALIZED_IMAGES = 6
}
