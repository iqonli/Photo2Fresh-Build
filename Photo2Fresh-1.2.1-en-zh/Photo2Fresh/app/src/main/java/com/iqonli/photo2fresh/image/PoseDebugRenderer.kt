package com.iqonli.photo2fresh.image

import com.iqonli.photo2fresh.i18n.AppLanguage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.iqonli.photo2fresh.inference.PancakePoseResult

object PoseDebugRenderer {
    fun render(
        source: Bitmap,
        result: PancakePoseResult,
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("无法创建检测卡定位模型输出图")
        val canvas = Canvas(output)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 220, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val boxPaint = Paint(outline).apply {
            color = Color.rgb(255, 190, 0)
            strokeWidth = 2f
        }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 64, 96)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 22f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        canvas.drawRect(
            result.box.left,
            result.box.top,
            result.box.right,
            result.box.bottom,
            boxPaint,
        )

        val corners = result.keypoints.take(4)
        if (corners.size == 4) {
            val path = Path().apply {
                moveTo(corners.first().point.x.toFloat(), corners.first().point.y.toFloat())
                corners.drop(1).forEach { point ->
                    lineTo(point.point.x.toFloat(), point.point.y.toFloat())
                }
                close()
            }
            canvas.drawPath(path, outline)
        }

        result.keypoints.forEachIndexed { index, keypoint ->
            val x = keypoint.point.x.toFloat()
            val y = keypoint.point.y.toFloat()
            canvas.drawCircle(x, y, 8f, pointPaint)
            canvas.drawText(
                AppLanguage.text("$index ${"%.3f".format(keypoint.confidence)}"),
                x + 12f,
                y - 10f,
                textPaint,
            )
        }
        canvas.drawText(
            AppLanguage.text("Pose ${"%.3f".format(result.confidence)}"),
            result.box.left.coerceAtLeast(8f),
            (result.box.top - 10f).coerceAtLeast(textPaint.textSize + 4f),
            textPaint,
        )
        return output
    }
}
