package com.iqonli.photo2fresh.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class LetterboxResult(
    val tensor: FloatBuffer,
    val inputSize: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

object YoloPreprocessor {
    fun bitmapToNchwFloat(
        bitmap: Bitmap,
        inputSize: Int,
        padValue: Int = 114,
    ): LetterboxResult {
        require(inputSize > 0)
        val scale = min(
            inputSize.toFloat() / bitmap.width.toFloat(),
            inputSize.toFloat() / bitmap.height.toFloat(),
        )
        val resizedWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (inputSize - resizedWidth) / 2f
        val padY = (inputSize - resizedHeight) / 2f

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.rgb(padValue, padValue, padValue))
            drawBitmap(
                bitmap,
                null,
                RectF(padX, padY, padX + resizedWidth, padY + resizedHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }

        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        letterboxed.recycle()

        val plane = inputSize * inputSize
        val buffer = ByteBuffer
            .allocateDirect(3 * plane * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (index in pixels.indices) buffer.put(index, Color.red(pixels[index]) / 255f)
        for (index in pixels.indices) buffer.put(plane + index, Color.green(pixels[index]) / 255f)
        for (index in pixels.indices) buffer.put(2 * plane + index, Color.blue(pixels[index]) / 255f)
        buffer.rewind()

        return LetterboxResult(
            tensor = buffer,
            inputSize = inputSize,
            scale = scale,
            padX = padX,
            padY = padY,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
        )
    }
}
