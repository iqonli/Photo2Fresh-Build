package com.iqonli.photo2fresh.inference

import com.iqonli.photo2fresh.opencv.ImagePoint
import kotlin.math.max
import kotlin.math.min

internal object YoloEndToEndDecoder {
    fun decodePose(
        data: FloatArray,
        transform: LetterboxResult,
        confidenceThreshold: Float,
        keypointCount: Int,
    ): PancakePoseResult {
        val width = 6 + keypointCount * 3
        require(data.size % width == 0) {
            "检测卡定位模型输出长度 ${data.size} 不能按 [xyxy,score,class,$keypointCount×(x,y,score)] 解码"
        }
        val rows = data.size / width
        var bestOffset = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (row in 0 until rows) {
            val offset = row * width
            val score = data[offset + 4]
            if (score.isFinite() && score >= confidenceThreshold && score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
        }
        require(bestOffset >= 0) { "检测卡定位模型未检出检测卡（conf=$confidenceThreshold）" }

        val coordinateFactor = coordinateFactor(data, bestOffset, width, transform.inputSize)
        val box = sourceBox(data, bestOffset, transform, coordinateFactor)
        val keypoints = List(keypointCount) { index ->
            val base = bestOffset + 6 + index * 3
            val x = sourceX(data[base] * coordinateFactor, transform)
            val y = sourceY(data[base + 1] * coordinateFactor, transform)
            PoseKeypoint(ImagePoint(x.toDouble(), y.toDouble()), data[base + 2])
        }
        require(keypoints.all { it.point.x.isFinite() && it.point.y.isFinite() }) {
            "检测卡定位模型关键点包含非有限值"
        }
        return PancakePoseResult(
            confidence = bestScore,
            classId = data[bestOffset + 5].toInt(),
            box = box,
            keypoints = keypoints,
        )
    }

    fun decodeSegRows(
        data: FloatArray,
        transform: LetterboxResult,
        coefficientCount: Int,
        confidenceThreshold: Float,
        iouThreshold: Float,
        maxDetections: Int,
    ): List<RawSegDetection> {
        val width = 6 + coefficientCount
        require(data.size % width == 0) {
            "传感区域分割模型输出长度 ${data.size} 不能按 [xyxy,score,class,$coefficientCount×mask] 解码"
        }
        val rows = data.size / width
        val detections = buildList {
            for (row in 0 until rows) {
                val offset = row * width
                val score = data[offset + 4]
                if (!score.isFinite() || score < confidenceThreshold) continue
                val factor = coordinateFactor(data, offset, width, transform.inputSize)
                val inputBox = inputBox(data, offset, factor, transform.inputSize)
                if (inputBox.area <= 0f) continue
                add(
                    RawSegDetection(
                        classId = data[offset + 5].toInt(),
                        confidence = score,
                        inputBox = inputBox,
                        sourceBox = sourceBox(data, offset, transform, factor),
                        coefficients = FloatArray(coefficientCount) { index -> data[offset + 6 + index] },
                    ),
                )
            }
        }
        return classAwareNms(detections, iouThreshold, maxDetections)
    }

    private fun coordinateFactor(data: FloatArray, offset: Int, width: Int, inputSize: Int): Float {
        var largest = 0f
        for (index in 0 until min(width, 6)) {
            val value = data[offset + index]
            if (value.isFinite()) largest = max(largest, kotlin.math.abs(value))
        }
        return if (largest <= 2f) inputSize.toFloat() else 1f
    }

    private fun inputBox(data: FloatArray, offset: Int, factor: Float, inputSize: Int): DetectionBox {
        val x1 = (data[offset] * factor).coerceIn(0f, inputSize.toFloat())
        val y1 = (data[offset + 1] * factor).coerceIn(0f, inputSize.toFloat())
        val x2 = (data[offset + 2] * factor).coerceIn(0f, inputSize.toFloat())
        val y2 = (data[offset + 3] * factor).coerceIn(0f, inputSize.toFloat())
        require(x2 >= x1 && y2 >= y1) {
            "YOLO 输出不像 end-to-end xyxy：[$x1,$y1,$x2,$y2]"
        }
        return DetectionBox(x1, y1, x2, y2)
    }

    private fun sourceBox(
        data: FloatArray,
        offset: Int,
        transform: LetterboxResult,
        factor: Float,
    ): DetectionBox {
        val input = inputBox(data, offset, factor, transform.inputSize)
        return DetectionBox(
            sourceX(input.left, transform),
            sourceY(input.top, transform),
            sourceX(input.right, transform),
            sourceY(input.bottom, transform),
        )
    }

    internal fun sourceX(value: Float, transform: LetterboxResult): Float =
        ((value - transform.padX) / transform.scale).coerceIn(0f, (transform.sourceWidth - 1).toFloat())

    internal fun sourceY(value: Float, transform: LetterboxResult): Float =
        ((value - transform.padY) / transform.scale).coerceIn(0f, (transform.sourceHeight - 1).toFloat())

    private fun classAwareNms(
        source: List<RawSegDetection>,
        threshold: Float,
        maxDetections: Int,
    ): List<RawSegDetection> {
        val selected = mutableListOf<RawSegDetection>()
        for (candidate in source.sortedByDescending(RawSegDetection::confidence)) {
            if (selected.size >= maxDetections) break
            val overlaps = selected.any {
                it.classId == candidate.classId && iou(it.inputBox, candidate.inputBox) > threshold
            }
            if (!overlaps) selected += candidate
        }
        return selected
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

internal data class RawSegDetection(
    val classId: Int,
    val confidence: Float,
    val inputBox: DetectionBox,
    val sourceBox: DetectionBox,
    val coefficients: FloatArray,
)
