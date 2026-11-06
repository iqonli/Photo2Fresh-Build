package com.iqonli.photo2fresh.image

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.iqonli.photo2fresh.inference.HydraliskCandidate

object SegmentationDebugRenderer {
    fun render(
        source: Bitmap,
        candidates: List<HydraliskCandidate>,
        classNames: List<String>,
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("无法创建传感区域分割模型调试图")
        val canvas = Canvas(output)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = 18f
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
        }

        candidates.forEach { candidate ->
            val color = classColor(candidate.classId)
            outline.color = color
            text.color = color
            val points = candidate.polygon
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points.first().x.toFloat(), points.first().y.toFloat())
                    points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                    close()
                }
                canvas.drawPath(path, outline)
            } else {
                canvas.drawRect(
                    candidate.box.left,
                    candidate.box.top,
                    candidate.box.right,
                    candidate.box.bottom,
                    outline,
                )
            }
            val name = classNames.getOrElse(candidate.classId) { "class_${candidate.classId}" }
            canvas.drawText(
                AppLanguage.text("$name ${"%.3f".format(candidate.confidence)}"),
                candidate.box.left.coerceAtLeast(2f),
                (candidate.box.top - 4f).coerceAtLeast(text.textSize),
                text,
            )
        }
        return output
    }

    private fun classColor(classId: Int): Int {
        val hue = ((classId * 47) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
    }
}
