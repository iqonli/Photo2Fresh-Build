package com.iqonli.photo2fresh.regression

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeploymentPixelTest {
    @Test fun productionInputMatchesBothDeploymentGoldenTensors() {
        val fixture=JSONObject(File("src/main/assets/肉类新鲜度/预处理自检.json").readText())
        val variants=fixture.getJSONArray("variants")
        for (v in 0 until variants.length()) {
            val variant=variants.getJSONObject(v)
            val patches=RoiImagePreprocessor.slotNames.mapIndexed { i,name ->
                val cfg=fixture.getJSONObject("rois").getJSONObject(name)
                val w=cfg.getInt("width"); val h=cfg.getInt("height")
                val pixels=IntArray(w*h) { k ->
                    fun c(channel:Int)=(17*(k%w)+29*(k/w)+71*channel+37*i)%256
                    (255 shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
                }
                val gains=if(variant.getString("pixel_state")=="raw") DoubleArray(3){cfg.getJSONArray("gains").getDouble(it)} else doubleArrayOf(1.0,1.0,1.0)
                RawRoiPatch(name,RgbImage(w,h,pixels),gains)
            }
            val x=RoiImagePreprocessor.prepare(patches).nchw()
            val bytes=ByteBuffer.allocate(x.size*4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(x)
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString(""){"%02x".format(it.toInt() and 255)}
            assertEquals(variant.getString("nchw_float32_little_endian_sha256"),hash)
        }
    }
}
