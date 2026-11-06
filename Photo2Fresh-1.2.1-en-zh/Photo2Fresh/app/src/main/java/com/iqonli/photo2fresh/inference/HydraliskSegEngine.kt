package com.iqonli.photo2fresh.inference

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import com.iqonli.photo2fresh.config.SegmentationConfig
import com.iqonli.photo2fresh.opencv.ImagePoint
import com.iqonli.photo2fresh.opencv.OpenCvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

class HydraliskSegEngine(
    context: Context,
    private val baseConfig: SegmentationConfig,
) : Closeable {
    private val session = YoloOnnxSession(
        context = context,
        assetPath = baseConfig.model.assetPath,
        inputSize = baseConfig.model.inputSize,
        padValue = baseConfig.model.letterboxValue,
    )

    fun infer(bitmap: Bitmap, config: SegmentationConfig = baseConfig): List<HydraliskCandidate> = session.run(bitmap) { result, transform ->
        check(OpenCvRuntime.initialized) { OpenCvRuntime.error ?: "OpenCV 尚未初始化" }
        require(result.size() == 2) { "传感区域分割模型应有两个输出，实际 ${result.size()}" }
        val detectionsTensor = result.get(0) as? OnnxTensor ?: error("传感区域分割模型 output0 不是张量")
        val prototypesTensor = result.get(1) as? OnnxTensor ?: error("传感区域分割模型 output1 不是张量")
        val detectionShape = detectionsTensor.tensorShape()
        val prototypeShape = prototypesTensor.tensorShape()
        require(detectionShape.contentEquals(longArrayOf(1, 300, 38))) {
            "传感区域分割模型 output0 应为 [1,300,38]，实际 ${detectionShape.contentToString()}"
        }
        require(prototypeShape.size == 4 && prototypeShape[0] == 1L && prototypeShape[1] == 32L) {
            "传感区域分割模型 output1 应为 [1,32,H,W]，实际 ${prototypeShape.contentToString()}"
        }

        val protoChannels = prototypeShape[1].toInt()
        val protoHeight = prototypeShape[2].toInt()
        val protoWidth = prototypeShape[3].toInt()
        val detections = YoloEndToEndDecoder.decodeSegRows(
            data = detectionsTensor.copyFloatArray(),
            transform = transform,
            coefficientCount = protoChannels,
            confidenceThreshold = config.model.confidence,
            iouThreshold = config.iouThreshold,
            maxDetections = config.maxDetections,
        )
        buildCandidates(
            detections = detections,
            prototypes = prototypesTensor.copyFloatArray(),
            protoChannels = protoChannels,
            protoHeight = protoHeight,
            protoWidth = protoWidth,
            transform = transform,
            maskThreshold = config.maskThreshold,
        )
    }

    private fun buildCandidates(
        detections: List<RawSegDetection>,
        prototypes: FloatArray,
        protoChannels: Int,
        protoHeight: Int,
        protoWidth: Int,
        transform: LetterboxResult,
        maskThreshold: Float,
    ): List<HydraliskCandidate> {
        require(prototypes.size == protoChannels * protoHeight * protoWidth)
        require(maskThreshold > 0f && maskThreshold < 1f) { "maskThreshold 必须在 0 与 1 之间" }
        val prototypeMatrix = Mat(protoChannels, protoHeight * protoWidth, CvType.CV_32F)
        prototypeMatrix.put(0, 0, prototypes)
        val logitThreshold = ln(maskThreshold / (1f - maskThreshold)).toDouble()
        try {
            return detections.map { detection ->
                val polygon = decodePolygon(
                    detection = detection,
                    prototypeMatrix = prototypeMatrix,
                    protoHeight = protoHeight,
                    protoWidth = protoWidth,
                    transform = transform,
                    logitThreshold = logitThreshold,
                )
                HydraliskCandidate(
                    classId = detection.classId,
                    confidence = detection.confidence,
                    box = detection.sourceBox,
                    polygon = polygon,
                )
            }
        } finally {
            prototypeMatrix.release()
        }
    }

    private fun decodePolygon(
        detection: RawSegDetection,
        prototypeMatrix: Mat,
        protoHeight: Int,
        protoWidth: Int,
        transform: LetterboxResult,
        logitThreshold: Double,
    ): List<ImagePoint> {
        val coefficients = Mat(1, detection.coefficients.size, CvType.CV_32F)
        val flattened = Mat()
        val empty = Mat()
        var logits2d: Mat? = null
        val binaryFloat = Mat()
        val binary = Mat()
        val cropped = Mat.zeros(protoHeight, protoWidth, CvType.CV_8U)
        try {
            coefficients.put(0, 0, detection.coefficients)
            Core.gemm(coefficients, prototypeMatrix, 1.0, empty, 0.0, flattened)
            logits2d = flattened.reshape(1, protoHeight)
            Imgproc.threshold(logits2d, binaryFloat, logitThreshold, 255.0, Imgproc.THRESH_BINARY)
            binaryFloat.convertTo(binary, CvType.CV_8U)

            val scaleX = protoWidth.toFloat() / transform.inputSize.toFloat()
            val scaleY = protoHeight.toFloat() / transform.inputSize.toFloat()
            val left = floor(detection.inputBox.left * scaleX).toInt().coerceIn(0, protoWidth - 1)
            val top = floor(detection.inputBox.top * scaleY).toInt().coerceIn(0, protoHeight - 1)
            val right = ceil(detection.inputBox.right * scaleX).toInt().coerceIn(left + 1, protoWidth)
            val bottom = ceil(detection.inputBox.bottom * scaleY).toInt().coerceIn(top + 1, protoHeight)
            val roi = Rect(left, top, right - left, bottom - top)
            binary.submat(roi).use { sourceRoi ->
                cropped.submat(roi).use { targetRoi -> sourceRoi.copyTo(targetRoi) }
            }

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            val contourInput = cropped.clone()
            try {
                Imgproc.findContours(
                    contourInput,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE,
                )
                val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largest == null || Imgproc.contourArea(largest) < 1.0) {
                    return boxPolygon(detection.sourceBox)
                }
                val approx2f = org.opencv.core.MatOfPoint2f()
                val largest2f = org.opencv.core.MatOfPoint2f(*largest.toArray())
                try {
                    val epsilon = 0.004 * Imgproc.arcLength(largest2f, true)
                    Imgproc.approxPolyDP(largest2f, approx2f, epsilon, true)
                    return approx2f.toArray().map { point ->
                        val modelX = point.x.toFloat() * transform.inputSize.toFloat() / protoWidth.toFloat()
                        val modelY = point.y.toFloat() * transform.inputSize.toFloat() / protoHeight.toFloat()
                        ImagePoint(
                            YoloEndToEndDecoder.sourceX(modelX, transform).toDouble(),
                            YoloEndToEndDecoder.sourceY(modelY, transform).toDouble(),
                        )
                    }.ifEmpty { boxPolygon(detection.sourceBox) }
                } finally {
                    approx2f.release()
                    largest2f.release()
                }
            } finally {
                contourInput.release()
                hierarchy.release()
                contours.forEach(MatOfPoint::release)
            }
        } finally {
            coefficients.release()
            flattened.release()
            empty.release()
            logits2d?.release()
            binaryFloat.release()
            binary.release()
            cropped.release()
        }
    }

    private fun boxPolygon(box: DetectionBox): List<ImagePoint> = listOf(
        ImagePoint(box.left.toDouble(), box.top.toDouble()),
        ImagePoint(box.right.toDouble(), box.top.toDouble()),
        ImagePoint(box.right.toDouble(), box.bottom.toDouble()),
        ImagePoint(box.left.toDouble(), box.bottom.toDouble()),
    )

    override fun close() = session.close()
}

private inline fun <T : Mat, R> T.use(block: (T) -> R): R = try {
    block(this)
} finally {
    release()
}
