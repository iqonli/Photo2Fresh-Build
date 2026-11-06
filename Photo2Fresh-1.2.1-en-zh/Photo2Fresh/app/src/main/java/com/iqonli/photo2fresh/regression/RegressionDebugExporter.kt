package com.iqonli.photo2fresh.regression

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RegressionDebugExporter {
    fun export(context: Context, patches: List<RawRoiPatch>, images: RegressionImages, condition: RegressionCondition, flags: List<String>, geometryJson: String, source: Bitmap): String {
        val directory = File(context.cacheDir, "regression_debug").apply { mkdirs() }
        val output = File(directory, "regression_sample_${System.nanoTime()}.zip")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                entry("geometry.json", geometryJson.toByteArray(Charsets.UTF_8))
                ByteArrayOutputStream().use { out ->
                    check(source.compress(Bitmap.CompressFormat.PNG, 100, out))
                    entry("rectified_source.png", out.toByteArray())
                }
                val rois = JSONArray()
                patches.forEach { patch ->
                    val name = "raw_rois/${patch.name}.png"
                    entry(name, png(patch.image))
                    rois.put(JSONObject().put("name", patch.name).put("file", name)
                        .put("width", patch.image.width).put("height", patch.image.height)
                        .put("gain", JSONArray(patch.gains.toList())))
                }
                entry("mosaic192.png", png(images.mosaic192))
                entry("input224.png", png(images.input224))
                val values = images.nchw()
                val tensor = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { tensor.putFloat(it) }
                entry("input_nchw.f32", tensor.array())
                entry("sample.json", JSONObject().put("schemaVersion", 1)
                    .put("preprocessingVersion", RoiImagePreprocessor.VERSION)
                    .put("meat", condition.meat.key).put("temperatureC", condition.temperature.celsius)
                    .put("rois", rois).put("qualityFlags", JSONArray(flags))
                    .put("rawRoisAlreadyCorrected", false)
                    .put("geometryParityVerified", false)
                    .put("tensorShape", JSONArray(listOf(1, 3, 224, 224)))
                    .put("tensorDtype", "float32_little_endian").toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) { output.delete(); throw e }
        directory.listFiles()?.filter { it.extension == "zip" }?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output).toString()
    }

    private fun png(image: RgbImage): ByteArray {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
