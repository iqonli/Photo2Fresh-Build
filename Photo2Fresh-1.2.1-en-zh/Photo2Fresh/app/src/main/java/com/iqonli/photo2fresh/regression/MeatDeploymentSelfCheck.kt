package com.iqonli.photo2fresh.regression

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** 在目标手机后台线程执行一次；只用合成图案，不需要实验照片或真实标签。 */
object MeatDeploymentSelfCheck {
    fun run(context: Context, directory: String = "肉类新鲜度"): List<String> {
        val assets = context.assets
        val fixture = JSONObject(assets.open("$directory/预处理自检.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val rois = linkedMapOf<String, RoiRgb>()
        val gains = linkedMapOf<String, DoubleArray>()
        for ((i, name) in MeatImagePreprocessor.names.withIndex()) {
            val cfg = fixture.getJSONObject("rois").getJSONObject(name)
            val w = cfg.getInt("width"); val h = cfg.getInt("height")
            val pixels = ByteArray(w * h * 3) { k ->
                val p = k / 3; val c = k % 3
                ((17 * (p % w) + 29 * (p / w) + 71 * c + 37 * i) % 256).toByte()
            }
            rois[name] = RoiRgb(w, h, pixels)
            gains[name] = DoubleArray(3) { cfg.getJSONArray("gains").getDouble(it) }
        }
        val passed = mutableListOf<String>()
        YoloImageRegressor(context, RegressionConfig("$directory/模型配置.json")).use { engine ->
            val variants = fixture.getJSONArray("variants")
            for (i in 0 until variants.length()) {
                val variant = variants.getJSONObject(i)
                val state = variant.getString("pixel_state")
                val x = if (state == "raw") MeatImagePreprocessor.fromRawRois(rois, gains)
                        else MeatImagePreprocessor.fromCorrectedRois(rois)
                val patches = MeatImagePreprocessor.names.map { name ->
                    val roi = rois.getValue(name)
                    val pixels = IntArray(roi.width * roi.height) { k ->
                        (255 shl 24) or ((roi.pixels[k*3].toInt() and 255) shl 16) or
                            ((roi.pixels[k*3+1].toInt() and 255) shl 8) or (roi.pixels[k*3+2].toInt() and 255)
                    }
                    RawRoiPatch(name, RgbImage(roi.width, roi.height, pixels), if (state == "raw") gains.getValue(name) else doubleArrayOf(1.0, 1.0, 1.0))
                }
                val images = RoiImagePreprocessor.prepare(patches)
                check(images.nchw().contentEquals(x)) { "正式预处理与部署包参考不一致" }
                val bytes = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(x)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
                check(hash == variant.getString("nchw_float32_little_endian_sha256")) { "$state 像素预处理与桌面基准不一致" }
                val expected = variant.getJSONArray("expected_predictions")
                for (j in 0 until expected.length()) {
                    val e = expected.getJSONObject(j); val id = e.getString("route_id")
                    val meat = when (id.substringBefore('_')) { "pork" -> "猪肉"; "beef" -> "牛肉"; "chicken" -> "鸡肉"; else -> error(id) }
                    val temp = id.substringAfter('_').removeSuffix("c").toInt()
                    val condition = RegressionCondition(MeatType.entries.single { it.label == meat }, StorageTemperature.entries.single { it.celsius == temp })
                    val actual = engine.predict(images, condition)
                    for ((value, reference) in listOf(actual.tvbn.toDouble() to e.getDouble("tvbn_mg_per_100g"), actual.timeHours.toDouble() to e.getDouble("storage_time_h"))) {
                        val limit = fixture.getDouble("prediction_absolute_tolerance") + fixture.getDouble("prediction_relative_tolerance") * abs(reference)
                        check(abs(value - reference) <= limit) { "$state $id 推理值与桌面基准不一致" }
                    }
                    val expectedLabel = when(e.getString("freshness")) { "Fresh" -> "新鲜"; "Less Fresh" -> "次新鲜"; "Spoiled" -> "腐败"; else -> error("未知等级") }
                    check(actual.freshness == expectedLabel)
                    passed.add("$state/$id OK")
                }
            }
        }
        return passed
    }
}
