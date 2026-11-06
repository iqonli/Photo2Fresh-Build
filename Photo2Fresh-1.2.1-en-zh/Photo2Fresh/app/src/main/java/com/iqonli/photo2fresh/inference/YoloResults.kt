package com.iqonli.photo2fresh.inference

import com.iqonli.photo2fresh.opencv.ImagePoint

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
}

data class PoseKeypoint(
    val point: ImagePoint,
    val confidence: Float,
)

data class PancakePoseResult(
    val confidence: Float,
    val classId: Int,
    val box: DetectionBox,
    val keypoints: List<PoseKeypoint>,
)

data class HydraliskCandidate(
    val classId: Int,
    val confidence: Float,
    val box: DetectionBox,
    val polygon: List<ImagePoint>,
)
