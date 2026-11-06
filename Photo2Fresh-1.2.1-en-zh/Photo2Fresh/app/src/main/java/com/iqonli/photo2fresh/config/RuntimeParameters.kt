package com.iqonli.photo2fresh.config

/**
 * Parameters that may be changed only from the test interface.
 * They are deliberately kept in memory and reset to the asset defaults on every process launch.
 *
 * Values entered manually are intentionally NOT clamped here. The test UI warns when a value is
 * outside the normal range, but the raw value is still passed to the pipeline. Any algorithm that
 * cannot operate on that value will fail inside the normal pipeline exception boundary instead of
 * crashing the app process.
 */
data class RuntimeParameters(
    val poseConfidence: Float,
    val segCandidateConfidence: Float,
    val segIouThreshold: Float,
    val segMaxDetections: Int,
    val segMaskThreshold: Float,
    val rainbowRoiScale: Double,
    val rainbowRansacIterations: Int,
    val rainbowResidualRatio: Double,
    val rainbowMinInliers: Int,
    val rainbowMaxRecover: Int,
    val calibrationStrength: Double,
    val gainMin: Double,
    val gainMax: Double,
) {
    fun applyTo(defaults: WorkflowConfig): WorkflowConfig = defaults.copy(
        pose = defaults.pose.copy(
            model = defaults.pose.model.copy(confidence = poseConfidence),
        ),
        segmentation = defaults.segmentation.copy(
            model = defaults.segmentation.model.copy(confidence = segCandidateConfidence),
            iouThreshold = segIouThreshold,
            maxDetections = segMaxDetections,
            maskThreshold = segMaskThreshold,
        ),
        rainbow = defaults.rainbow.copy(
            roiScale = rainbowRoiScale,
            ransacIterations = rainbowRansacIterations,
            residualStepRatio = rainbowResidualRatio,
            minInliers = rainbowMinInliers,
            maxRecover = rainbowMaxRecover,
            calibrationStrength = calibrationStrength,
            gainMin = gainMin,
            gainMax = gainMax,
        ),
    )

    companion object {
        fun from(config: WorkflowConfig): RuntimeParameters = RuntimeParameters(
            poseConfidence = config.pose.model.confidence,
            segCandidateConfidence = config.segmentation.model.confidence,
            segIouThreshold = config.segmentation.iouThreshold,
            segMaxDetections = config.segmentation.maxDetections,
            segMaskThreshold = config.segmentation.maskThreshold,
            rainbowRoiScale = config.rainbow.roiScale,
            rainbowRansacIterations = config.rainbow.ransacIterations,
            rainbowResidualRatio = config.rainbow.residualStepRatio,
            rainbowMinInliers = config.rainbow.minInliers,
            rainbowMaxRecover = config.rainbow.maxRecover,
            calibrationStrength = config.rainbow.calibrationStrength,
            gainMin = config.rainbow.gainMin,
            gainMax = config.rainbow.gainMax,
        )
    }
}
