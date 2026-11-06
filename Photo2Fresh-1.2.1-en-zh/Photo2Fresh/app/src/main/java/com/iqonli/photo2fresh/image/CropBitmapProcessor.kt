package com.iqonli.photo2fresh.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.iqonli.photo2fresh.pipeline.CropEditState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

object CropBitmapProcessor {
    fun apply(source: Bitmap, editState: CropEditState): Bitmap {
        val state = editState.normalized()
        val rect = cropPixelRect(source, state)
        val rotated = rotateAroundCropCenter(
            source = source,
            degrees = state.rotationDegrees,
            pivotX = (rect.left + rect.right) / 2f,
            pivotY = (rect.top + rect.bottom) / 2f,
        )
        return try {
            Bitmap.createBitmap(rotated, rect.left, rect.top, rect.width, rect.height)
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    /**
     * Rotate the source around the selected crop region center while keeping the source canvas size.
     * The crop frame therefore remains the anchor, matching the editor preview.
     */
    private fun rotateAroundCropCenter(
        source: Bitmap,
        degrees: Float,
        pivotX: Float,
        pivotY: Float,
    ): Bitmap {
        if (abs(degrees) < 0.0001f) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix().apply {
            postRotate(degrees, pivotX, pivotY)
        }
        canvas.drawBitmap(
            source,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    private fun cropPixelRect(source: Bitmap, state: CropEditState): PixelRect {
        val minSizePx = 2
        val left = (state.left * source.width).toInt().coerceIn(0, max(0, source.width - minSizePx))
        val top = (state.top * source.height).toInt().coerceIn(0, max(0, source.height - minSizePx))
        val right = ceil(state.right * source.width).toInt().coerceIn(left + minSizePx, source.width)
        val bottom = ceil(state.bottom * source.height).toInt().coerceIn(top + minSizePx, source.height)
        return PixelRect(left, top, right, bottom)
    }

    private data class PixelRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
}
