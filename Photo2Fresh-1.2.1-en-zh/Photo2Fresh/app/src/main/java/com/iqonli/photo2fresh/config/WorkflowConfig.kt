package com.iqonli.photo2fresh.config

import android.content.Context
import org.json.JSONObject
import com.iqonli.photo2fresh.regression.RegressionConfig

data class ModelAssetConfig(
    val displayName: String,
    val assetPath: String,
    val inputSize: Int,
    val confidence: Float,
    val letterboxValue: Int,
)

data class PoseConfig(
    val model: ModelAssetConfig,
    val keypointCount: Int,
)

data class RectificationConfig(
    val canvasSize: Int,
    val squareSize: Int,
    val outputCodec: String,
    val jpegQuality: Int,
    val interpolation: String,
)

data class SegmentationConfig(
    val model: ModelAssetConfig,
    val iouThreshold: Float,
    val maxDetections: Int,
    val maskThreshold: Float,
    val classNames: List<String>,
    val parameterNote: String,
)

data class RainbowConfig(
    val roiScale: Double,
    val ransacIterations: Int,
    val residualStepRatio: Double,
    val minInliers: Int,
    val maxRecover: Int,
    val calibrationStrength: Double,
    val gainMin: Double,
    val gainMax: Double,
    val standardGrayRgb: IntArray,
    val parameterNote: String,
)

data class WorkflowConfig(
    val configVersion: Int,
    val developmentMode: Boolean,
    val pose: PoseConfig,
    val rectification: RectificationConfig,
    val segmentation: SegmentationConfig,
    val rainbow: RainbowConfig,
    val regression: RegressionConfig,
) {
    companion object {
        fun load(context: Context): WorkflowConfig {
            val text = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val pose = root.getJSONObject("pose")
            val rectification = root.getJSONObject("rectification")
            val segmentation = root.getJSONObject("segmentation")
            val rainbow = root.getJSONObject("rainbow")
            val regression = root.getJSONObject("regression")
            val gray = rainbow.getJSONArray("standardGrayRgb")

            return WorkflowConfig(
                configVersion = root.getInt("configVersion"),
                developmentMode = root.optBoolean("developmentMode", true),
                pose = PoseConfig(
                    model = ModelAssetConfig(
                        displayName = pose.getString("displayName"),
                        assetPath = pose.getString("assetPath"),
                        inputSize = pose.getInt("inputSize"),
                        confidence = pose.getDouble("confidence").toFloat(),
                        letterboxValue = pose.getInt("letterboxValue"),
                    ),
                    keypointCount = pose.getInt("keypointCount"),
                ),
                rectification = RectificationConfig(
                    canvasSize = rectification.getInt("canvasSize"),
                    squareSize = rectification.getInt("squareSize"),
                    outputCodec = rectification.getString("outputCodec"),
                    jpegQuality = rectification.getInt("jpegQuality"),
                    interpolation = rectification.getString("interpolation"),
                ),
                segmentation = SegmentationConfig(
                    model = ModelAssetConfig(
                        displayName = segmentation.getString("displayName"),
                        assetPath = segmentation.getString("assetPath"),
                        inputSize = segmentation.getInt("inputSize"),
                        confidence = segmentation.getDouble("candidateConfidence").toFloat(),
                        letterboxValue = segmentation.getInt("letterboxValue"),
                    ),
                    iouThreshold = segmentation.getDouble("iouThreshold").toFloat(),
                    maxDetections = segmentation.getInt("maxDetections"),
                    maskThreshold = segmentation.getDouble("maskThreshold").toFloat(),
                    classNames = segmentation.getJSONArray("classNames").let { names ->
                        List(names.length()) { index -> names.getString(index) }
                    },
                    parameterNote = segmentation.getString("parameterNote"),
                ),
                rainbow = RainbowConfig(
                    roiScale = rainbow.getDouble("roiScale"),
                    ransacIterations = rainbow.getInt("ransacIterations"),
                    residualStepRatio = rainbow.getDouble("residualStepRatio"),
                    minInliers = rainbow.getInt("minInliers"),
                    maxRecover = rainbow.getInt("maxRecover"),
                    calibrationStrength = rainbow.getDouble("calibrationStrength"),
                    gainMin = rainbow.getDouble("gainMin"),
                    gainMax = rainbow.getDouble("gainMax"),
                    standardGrayRgb = intArrayOf(gray.getInt(0), gray.getInt(1), gray.getInt(2)),
                    parameterNote = rainbow.getString("parameterNote"),
                ),
                regression = RegressionConfig(regression.getString("manifestAssetPath")),
            ).also { config ->
                require(config.pose.keypointCount >= 4) { "检测卡定位模型关键点至少需要 4 个" }
                require(config.rectification.squareSize in 1..config.rectification.canvasSize) { "矫正正方形尺寸无效" }
                require(config.rectification.jpegQuality in 1..100) { "JPEG 质量必须为 1..100" }
                require(config.segmentation.model.confidence in 0f..1f) { "传感区域分割模型 candidate_conf 无效" }
                require(config.segmentation.iouThreshold in 0f..1f) { "传感区域分割模型 IoU 无效" }
                require(config.segmentation.maskThreshold > 0f && config.segmentation.maskThreshold < 1f) { "传感区域分割模型 maskThreshold 无效" }
                require(config.segmentation.maxDetections > 0) { "传感区域分割模型 maxDetections 必须大于 0" }
                require(config.rainbow.roiScale in 0.0..1.0) { "颜色提取与校正模块 ROI 比例无效" }
                require(config.rainbow.minInliers in 1..9) { "颜色提取与校正模块最少内点必须为 1..9" }
                require(config.rainbow.maxRecover in 0..9) { "颜色提取与校正模块最多补全必须为 0..9" }
                require(config.rainbow.gainMin > 0.0 && config.rainbow.gainMin <= config.rainbow.gainMax) { "颜色提取与校正模块增益范围无效" }
            }
        }

        private const val CONFIG_ASSET = "workflow_config.json"
    }
}
