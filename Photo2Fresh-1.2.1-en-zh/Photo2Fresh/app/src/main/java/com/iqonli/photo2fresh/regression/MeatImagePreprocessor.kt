package com.iqonli.photo2fresh.regression

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 像素顺序为RGBRGB...；使用不含alpha的原始8位RGB数据。 */
data class RoiRgb(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height * 3) }
}

object MeatImagePreprocessor {
    val names = listOf("C11", "C12", "C13", "C21", "C22", "C23", "C31", "C32", "C33")
    private const val bits = 22
    private const val one = 1 shl bits
    private data class Kernel(val start: Int, val weights: IntArray)

    private fun kernels(input: Int, output: Int): List<Kernel> {
        val ratio = input.toDouble() / output
        val support = max(1.0, ratio)
        val reciprocal = 1.0 / support
        return List(output) { i ->
            val center = (i + 0.5) * ratio
            val start = max(0, (center - support + 0.5).toInt())
            val end = min(input, (center + support + 0.5).toInt())
            val raw = DoubleArray(end - start) { k -> max(0.0, 1.0 - abs((k + start - center + 0.5) * reciprocal)) }
            val total = raw.sum()
            Kernel(start, IntArray(raw.size) { k -> (0.5 + raw[k] / total * one).toInt() })
        }
    }

    /** 分离双线性滤波，降采样扩宽滤波范围；每一级8位量化，匹配训练的Pillow路径。 */
    private fun resize(src: RoiRgb, width: Int, height: Int): RoiRgb {
        var data = src.pixels
        if (src.width != width) {
            val ks = kernels(src.width, width); val next = ByteArray(width * src.height * 3)
            for (y in 0 until src.height) for (x in 0 until width) for (c in 0..2) {
                val k = ks[x]; var value = one / 2
                for (j in k.weights.indices) value += (data[(y * src.width + k.start + j) * 3 + c].toInt() and 255) * k.weights[j]
                next[(y * width + x) * 3 + c] = (value shr bits).coerceIn(0, 255).toByte()
            }
            data = next
        }
        if (src.height != height) {
            val ks = kernels(src.height, height); val next = ByteArray(width * height * 3)
            for (y in 0 until height) for (x in 0 until width) for (c in 0..2) {
                val k = ks[y]; var value = one / 2
                for (j in k.weights.indices) value += (data[((k.start + j) * width + x) * 3 + c].toInt() and 255) * k.weights[j]
                next[(y * width + x) * 3 + c] = (value shr bits).coerceIn(0, 255).toByte()
            }
            data = next
        }
        return RoiRgb(width, height, data)
    }

    private fun correct(src: RoiRgb, gains: DoubleArray): RoiRgb {
        require(gains.size == 3 && gains.all { it.isFinite() && it > 0.0 })
        // 对8位输入生成逐通道查找表，仍执行与训练相同的双精度公式。
        val table = Array(3) { c -> IntArray(256) { v ->
            val s = v / 255.0
            val linear = if (s <= 0.04045) s / 12.92 else StrictMath.pow((s + 0.055) / 1.055, 2.4)
            val fixed = (linear * gains[c]).coerceIn(0.0, 1.0)
            val encoded = if (fixed <= 0.0031308) 12.92 * fixed else 1.055 * StrictMath.pow(fixed, 1.0 / 2.4) - 0.055
            Math.rint(encoded * 255.0).toInt().coerceIn(0, 255)
        } }
        return RoiRgb(src.width, src.height, ByteArray(src.pixels.size) { i -> table[i % 3][src.pixels[i].toInt() and 255].toByte() })
    }

    private fun build(rois: Map<String, RoiRgb>, gains: Map<String, DoubleArray>?): FloatArray {
        require(rois.keys == names.toSet())
        if (gains != null) require(gains.keys == names.toSet())
        val canvas = ByteArray(192 * 192 * 3)
        for ((i, name) in names.withIndex()) {
            val roi = rois.getValue(name)
            val corrected = if (gains == null) roi else correct(roi, gains.getValue(name))
            val small = resize(corrected, 64, 64)
            for (y in 0 until 64) System.arraycopy(small.pixels, y * 64 * 3, canvas, ((i / 3 * 64 + y) * 192 + i % 3 * 64) * 3, 64 * 3)
        }
        val rgb = resize(RoiRgb(192, 192, canvas), 224, 224).pixels
        val nchw = FloatArray(3 * 224 * 224)
        for (p in 0 until 224 * 224) for (c in 0..2) nchw[c * 224 * 224 + p] = (rgb[p * 3 + c].toInt() and 255) / 255f
        return nchw
    }

    fun fromRawRois(rois: Map<String, RoiRgb>, gains: Map<String, DoubleArray>): FloatArray = build(rois, gains)
    fun fromCorrectedRois(rois: Map<String, RoiRgb>): FloatArray = build(rois, null)
}
