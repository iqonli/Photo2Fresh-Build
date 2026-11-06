package com.iqonli.photo2fresh.util

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

fun saveImageToGallery(context: Context, source: Uri, displayName: String): Result<Uri> = runCatching {
    val mimeType = context.contentResolver.getType(source) ?: "image/jpeg"
    val extension = when (mimeType.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/heic", "image/heif" -> ".heic"
        else -> ".jpg"
    }
    val baseName = displayName
        .substringBeforeLast('.', displayName)
        .replace(Regex("[^0-9A-Za-z._-]+"), "_")
        .trim('_')
        .ifBlank { "Photo2Fresh_${System.currentTimeMillis()}" }
    val safeName = "$baseName$extension"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Photo2Fresh")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val output = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "无法创建系统图片记录" }
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "无法读取图片" }
                resolver.openOutputStream(output).use { stream ->
                    requireNotNull(stream) { "无法写入图片" }
                    input.copyTo(stream)
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(output, values, null, null)
            output
        } catch (exception: Exception) {
            resolver.delete(output, null, null)
            throw exception
        }
    } else {
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Photo2Fresh",
        ).apply { mkdirs() }
        val outputFile = File(directory, safeName)
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "无法读取图片" }
            FileOutputStream(outputFile).use { output -> input.copyTo(output) }
        }
        @Suppress("DEPRECATION")
        context.sendBroadcast(
            Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outputFile)),
        )
        Uri.fromFile(outputFile)
    }
}

fun shareImage(context: Context, source: Uri, chooserTitle: String = AppLanguage.text("分享图片")) {
    val shareUri = ensureShareableUri(context, source)
    val mimeType = context.contentResolver.getType(source) ?: "image/*"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

private fun ensureShareableUri(context: Context, source: Uri): Uri {
    if (source.scheme == "content") return source
    val directory = File(context.cacheDir, "shared").apply { mkdirs() }
    val output = File(directory, "shared_${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(source).use { input ->
        requireNotNull(input) { "无法读取待分享图片" }
        FileOutputStream(output).use { stream -> input.copyTo(stream) }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
}
