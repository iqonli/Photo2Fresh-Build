package com.iqonli.photo2fresh.opencv

import android.graphics.Bitmap
import com.iqonli.photo2fresh.config.RectificationConfig
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class ImagePoint(
    val x: Double,
    val y: Double,
)

data class RectificationResult(
    val bitmap: Bitmap,
    val homographyRowMajor: DoubleArray,
    val maxCornerResidualPx: Double,
)

object PerspectiveRectifier {
    /**
     * point0..point3 必须按检测卡定位模型训练时约定的顺序排列。
     * 此函数不会自行猜测角点身份，也不会使用第 5 个关键点。
     */
    fun rectify(
        sourceBitmap: Bitmap,
        orderedCorners: List<ImagePoint>,
        config: RectificationConfig,
    ): RectificationResult {
        check(OpenCvRuntime.initialized) { OpenCvRuntime.error ?: "OpenCV 尚未初始化" }
        validateCorners(sourceBitmap, orderedCorners)
        require(config.canvasSize > 0 && config.squareSize in 1..config.canvasSize)

        val source = Mat()
        val sourceRgba = Mat()
        val destination = Mat(
            config.canvasSize,
            config.canvasSize,
            CvType.CV_8UC4,
            Scalar(255.0, 255.0, 255.0, 255.0),
        )
        val srcPoints = MatOfPoint2f()
        val dstPoints = MatOfPoint2f()
        val transformedPoints = MatOfPoint2f()
        var homography: Mat? = null

        try {
            Utils.bitmapToMat(sourceBitmap, source)
            when (source.channels()) {
                4 -> source.copyTo(sourceRgba)
                3 -> Imgproc.cvtColor(source, sourceRgba, Imgproc.COLOR_RGB2RGBA)
                else -> throw IllegalArgumentException("不支持 ${source.channels()} 通道图片")
            }

            srcPoints.fromArray(*orderedCorners.map { Point(it.x, it.y) }.toTypedArray())
            val margin = (config.canvasSize - config.squareSize) / 2.0
            val end = margin + config.squareSize - 1.0
            dstPoints.fromArray(
                Point(margin, margin),
                Point(end, margin),
                Point(end, end),
                Point(margin, end),
            )

            homography = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            val matrix = homography
            val matrixValues = DoubleArray(9)
            matrix.get(0, 0, matrixValues)
            require(matrixValues.all { it.isFinite() }) { "透视矩阵包含非有限值" }
            require(abs(Core.determinant(matrix)) >= 1e-12) { "透视矩阵不可逆" }

            Imgproc.warpPerspective(
                sourceRgba,
                destination,
                matrix,
                Size(config.canvasSize.toDouble(), config.canvasSize.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_CONSTANT,
                Scalar(255.0, 255.0, 255.0, 255.0),
            )

            Core.perspectiveTransform(srcPoints, transformedPoints, matrix)
            val transformed = transformedPoints.toArray()
            val expected = dstPoints.toArray()
            val residual = transformed.indices.maxOf { index ->
                hypot(
                    transformed[index].x - expected[index].x,
                    transformed[index].y - expected[index].y,
                )
            }
            require(residual <= 0.05) { "透视映射残差异常：$residual px" }

            val output = Bitmap.createBitmap(
                config.canvasSize,
                config.canvasSize,
                Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(destination, output)
            return RectificationResult(output, matrixValues, residual)
        } finally {
            source.release()
            sourceRgba.release()
            destination.release()
            srcPoints.release()
            dstPoints.release()
            transformedPoints.release()
            homography?.release()
        }
    }

    private fun validateCorners(bitmap: Bitmap, points: List<ImagePoint>) {
        require(points.size == 4) { "透视矫正必须提供 4 个有序角点" }
        require(points.all { it.x.isFinite() && it.y.isFinite() }) { "角点包含非有限值" }
        require(points.all { it.x >= 0.0 && it.x < bitmap.width }) { "角点 x 超出图片范围" }
        require(points.all { it.y >= 0.0 && it.y < bitmap.height }) { "角点 y 超出图片范围" }

        val area = abs(
            points.indices.sumOf { index ->
                val current = points[index]
                val next = points[(index + 1) % points.size]
                current.x * next.y - next.x * current.y
            } / 2.0,
        )
        val minimumArea = max(25.0, bitmap.width.toDouble() * bitmap.height.toDouble() * 1e-6)
        require(area >= minimumArea) { "四角面积过小或近乎共线" }

        val crossProducts = points.indices.map { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        val allPositive = crossProducts.all { it > 0.0 }
        val allNegative = crossProducts.all { it < 0.0 }
        require(allPositive || allNegative) { "点 0→1→2→3 不是凸四边形，可能点序错误" }

        points.indices.forEach { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            require(hypot(a.x - b.x, a.y - b.y) >= 2.0) {
                "点 $index 与点 ${(index + 1) % points.size} 距离过小"
            }
        }
    }
}
