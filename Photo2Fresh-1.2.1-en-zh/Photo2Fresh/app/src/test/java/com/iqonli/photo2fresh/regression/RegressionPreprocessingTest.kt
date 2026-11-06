package com.iqonli.photo2fresh.regression

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RegressionPreprocessingTest {
    private data class Case(val w: Int, val h: Int, val ow: Int, val oh: Int, val hash: String)

    @Test fun resizeMatchesIndependentPillow123GoldenPixels() {
        val cases = listOf(
            Case(19, 27, 64, 64, "d00691fc8d859f94089ae8c73c90d58bf792948fca716e48d587013a78377a0e"),
            Case(225, 217, 64, 64, "c5f911078b1447f5aeec1981d242fc34eaf82f9d1398d33419dd909bffda5a79"),
            Case(192, 192, 224, 224, "ffaf8f7f7bc5880bdcff4caf19fbcbd83c7182ecb3b8063c2f7c688cca705bf2"),
            Case(1, 3, 64, 64, "42ad887b935598665e9b37fb09833bfb71c3e70a640e0bb928021668878025c2"),
        )
        cases.forEach { c ->
            val pixels = IntArray(c.w * c.h) { i ->
                val x = i % c.w; val y = i / c.w
                (255 shl 24) or (((17 * x + 13 * y) % 256) shl 16) or
                    (((3 * x + 29 * y + 71) % 256) shl 8) or ((37 * x + 7 * y + 121) % 256)
            }
            val result = PillowBilinear.resize(RgbImage(c.w, c.h, pixels), c.ow, c.oh)
            val bytes = ByteArray(result.pixels.size * 3)
            result.pixels.forEachIndexed { i, p -> bytes[3*i]=(p ushr 16).toByte(); bytes[3*i+1]=(p ushr 8).toByte(); bytes[3*i+2]=p.toByte() }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(c.hash, hash)
        }
    }

    @Test fun classificationAndElapsedTimeRespectBoundariesAndUnits() {
        fun result(value: Float, temp: StorageTemperature) = FreshnessPrediction(RegressionCondition(MeatType.PORK, temp), value, 48f, "test")
        assertEquals("新鲜", result(14.999f, StorageTemperature.ROOM).freshness)
        assertEquals("次新鲜", result(15f, StorageTemperature.ROOM).freshness)
        assertEquals("次新鲜", result(20f, StorageTemperature.ROOM).freshness)
        assertEquals("腐败", result(20.001f, StorageTemperature.ROOM).freshness)
        assertEquals(48f, result(15f, StorageTemperature.ROOM).displayedTime, 0f)
        assertEquals("小时", result(15f, StorageTemperature.ROOM).timeUnit)
        assertEquals(2f, result(15f, StorageTemperature.CHILLED).displayedTime, 0f)
        assertEquals("天", result(15f, StorageTemperature.CHILLED).timeUnit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOrMissingRoisCannotReachModel() {
        val roi=RawRoiPatch("C11",RgbImage(1,1,intArrayOf(-1)),doubleArrayOf(1.0,1.0,1.0))
        RoiImagePreprocessor.prepare(List(9) { roi })
    }
}
