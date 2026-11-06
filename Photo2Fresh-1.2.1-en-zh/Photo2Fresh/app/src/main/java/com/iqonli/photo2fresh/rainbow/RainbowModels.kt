package com.iqonli.photo2fresh.rainbow

import android.graphics.Bitmap
import com.iqonli.photo2fresh.regression.RawRoiPatch
import com.iqonli.photo2fresh.opencv.ImagePoint

data class RgbColor(
    val r: Double,
    val g: Double,
    val b: Double,
) {
    fun asArray(): DoubleArray = doubleArrayOf(r, g, b)
}

data class HslColor(
    val hDegrees: Double,
    val saturation: Double,
    val lightness: Double,
)

data class LabColor(
    val l: Double,
    val a: Double,
    val b: Double,
)

data class RainbowRegionColor(
    val name: String,
    val rawRgb: RgbColor,
    val correctedRgb: RgbColor,
    val lab: LabColor,
    val hsl: HslColor,
    val qualityFlags: List<String>,
)

data class RainbowGridSummary(
    val inliers: Int,
    val recovered: Int,
    val reassigned: Int,
    val medianResidualPx: Double,
    val thresholdPx: Double,
    val templateSource: String,
    val graySampleSource: String,
    val graySampleFallbackReason: String?,
)

data class RainbowResult(
    val colors: List<RainbowRegionColor>,
    val grid: RainbowGridSummary,
    val qualityFlags: List<String>,
    val debugBitmap: Bitmap,
    val assignedCenters: Map<String, ImagePoint>,
    val rawRois: List<RawRoiPatch>,
    val geometryJson: String,
)
