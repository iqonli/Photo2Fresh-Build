package com.iqonli.photo2fresh.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object PipelineBitmapIO {
    fun decode(context: Context, uriString: String): Bitmap {
        val uri = Uri.parse(uriString)
        val bitmap = context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "无法打开流水线图片" }
            BitmapFactory.decodeStream(stream)
        }
        return requireNotNull(bitmap) { "无法解码流水线图片" }
    }

    fun saveCropJpeg(
        context: Context,
        bitmap: Bitmap,
        quality: Int,
    ): String {
        val directory = File(context.cacheDir, "crops").apply { mkdirs() }
        val output = File(directory, "crop_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)) {
                "无法保存裁剪图片"
            }
        }
        directory.listFiles()
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_CACHED_CROPS)
            ?.forEach(File::delete)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            output,
        ).toString()
    }

    fun saveJpeg(
        context: Context,
        bitmap: Bitmap,
        prefix: String,
        quality: Int,
    ): String {
        val directory = File(context.cacheDir, "normalized").apply { mkdirs() }
        val output = File(directory, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), stream)) {
                "无法保存 $prefix 图片"
            }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            output,
        ).toString()
    }

    fun savePng(context: Context, bitmap: Bitmap, prefix: String): String {
        val directory = File(context.cacheDir, "regression_debug").apply { mkdirs() }
        val output = File(directory, "${prefix}_${System.nanoTime()}.png")
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "无法保存模型输入图" }
        }
        directory.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }?.drop(120)?.forEach { it.delete() }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output).toString()
    }

    private const val MAX_CACHED_CROPS = 8
}
