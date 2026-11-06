package com.iqonli.photo2fresh.regression

import kotlin.math.abs
import kotlin.math.max

/** Opaque RGB pixels packed as 0xffRRGGBB; contains no Android dependencies. */
data class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) { "RGB 图像尺寸无效" }
    }
}

data class RawRoiPatch(val name: String, val image: RgbImage, val gains: DoubleArray) {
    init {
        require(gains.size == 3 && gains.all { it.isFinite() && it > 0.0 }) { "ROI RGB 增益无效：$name" }
    }
}

data class RegressionImages(val mosaic192: RgbImage, val input224: RgbImage) {
    fun nchw(): FloatArray {
        val plane = input224.pixels.size
        return FloatArray(plane * 3).also { out ->
            input224.pixels.forEachIndexed { i, p ->
                out[i] = ((p ushr 16) and 255) / 255f
                out[plane + i] = ((p ushr 8) and 255) / 255f
                out[2 * plane + i] = (p and 255) / 255f
            }
        }
    }
}

object RoiImagePreprocessor {
    const val VERSION = "linear_srgb_rint_pillow_bilinear_v1"
    val slotNames = listOf("C11", "C12", "C13", "C21", "C22", "C23", "C31", "C32", "C33")

    fun prepare(patches: List<RawRoiPatch>): RegressionImages {
        require(patches.size == 9 && patches.map { it.name }.toSet() == slotNames.toSet()) { "必须提供 C11～C33 各一块原始 ROI" }
        val byName = patches.associateBy { it.name }
        val mosaic = IntArray(192 * 192)
        slotNames.forEachIndexed { slot, name ->
            val patch = byName.getValue(name)
            val tile = PillowBilinear.resize(correct(patch.image, patch.gains), 64, 64)
            val left = slot % 3 * 64
            val top = slot / 3 * 64
            for (y in 0 until 64) tile.pixels.copyInto(mosaic, (top + y) * 192 + left, y * 64, (y + 1) * 64)
        }
        val image = RgbImage(192, 192, mosaic)
        return RegressionImages(image, PillowBilinear.resize(image, 224, 224))
    }

    fun correct(image: RgbImage, gains: DoubleArray): RgbImage {
        require(gains.size == 3 && gains.all { it.isFinite() && it > 0.0 })
        // A per-channel LUT retains float64 arithmetic without three pow calls per pixel.
        val lut = Array(3) { c -> IntArray(256) { value ->
            val s = value / 255.0
            val linear = if (s <= 0.04045) s / 12.92 else StrictMath.pow((s + 0.055) / 1.055, 2.4)
            val corrected = (linear * gains[c]).coerceIn(0.0, 1.0)
            val srgb = if (corrected <= 0.0031308) 12.92 * corrected else 1.055 * StrictMath.pow(corrected, 1.0 / 2.4) - 0.055
            Math.rint(srgb * 255.0).toInt().coerceIn(0, 255)
        } }
        return RgbImage(image.width, image.height, IntArray(image.pixels.size) { i ->
            val p = image.pixels[i]
            (255 shl 24) or (lut[0][(p ushr 16) and 255] shl 16) or (lut[1][(p ushr 8) and 255] shl 8) or lut[2][p and 255]
        })
    }
}

/** Pillow RGB BILINEAR conventions: widened downsampling support, two uint8 passes,
 * normalized 22-bit coefficients and half-up convolution rounding. No Bitmap scaling. */
object PillowBilinear {
    private const val BITS = 22
    private const val ONE = 1 shl BITS
    private data class Kernel(val first: Int, val coefficients: IntArray)

    private fun kernels(size: Int, output: Int): List<Kernel> {
        val scale = size.toDouble() / output
        val filterScale = max(1.0, scale)
        return List(output) { i ->
            val center = (i + 0.5) * scale
            val first = (center - filterScale + 0.5).toInt().coerceAtLeast(0)
            val end = (center + filterScale + 0.5).toInt().coerceAtMost(size)
            val weights = DoubleArray(end - first) { x -> max(0.0, 1.0 - abs((x + first - center + 0.5) / filterScale)) }
            val sum = weights.sum()
            require(sum > 0.0)
            Kernel(first, IntArray(weights.size) { x -> (weights[x] / sum * ONE + 0.5).toInt() })
        }
    }

    fun resize(image: RgbImage, width: Int, height: Int): RgbImage {
        require(width > 0 && height > 0)
        var current = image
        if (width != current.width) {
            val input = current
            val ks = kernels(input.width, width)
            current = RgbImage(width, input.height, IntArray(width * input.height) { index ->
                val y = index / width
                val kernel = ks[index % width]
                sample(kernel) { x -> input.pixels[y * input.width + x] }
            })
        }
        if (height != current.height) {
            val input = current
            val ks = kernels(input.height, height)
            current = RgbImage(input.width, height, IntArray(input.width * height) { index ->
                val x = index % input.width
                sample(ks[index / input.width]) { y -> input.pixels[y * input.width + x] }
            })
        }
        return current
    }

    private inline fun sample(kernel: Kernel, pixel: (Int) -> Int): Int {
        var r = 1L shl (BITS - 1)
        var g = r
        var b = r
        kernel.coefficients.forEachIndexed { i, k ->
            val p = pixel(kernel.first + i)
            r += ((p ushr 16) and 255).toLong() * k
            g += ((p ushr 8) and 255).toLong() * k
            b += (p and 255).toLong() * k
        }
        return (255 shl 24) or ((r shr BITS).toInt().coerceIn(0, 255) shl 16) or
            ((g shr BITS).toInt().coerceIn(0, 255) shl 8) or (b shr BITS).toInt().coerceIn(0, 255)
    }
}
