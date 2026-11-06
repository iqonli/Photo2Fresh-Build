package com.iqonli.photo2fresh.pipeline

import com.iqonli.photo2fresh.image.PreparedImage
import com.iqonli.photo2fresh.inference.ModelDiagnostic
import com.iqonli.photo2fresh.regression.FreshnessPrediction
import com.iqonli.photo2fresh.regression.RegressionCondition
import com.iqonli.photo2fresh.rainbow.RainbowGridSummary
import com.iqonli.photo2fresh.rainbow.RainbowRegionColor

data class StageOutputImage(
    val title: String,
    val imageUri: String,
)

sealed interface PipelineState {
    data object Idle : PipelineState

    data class Ready(
        val imageUri: String,
    ) : PipelineState

    data class Processing(
        val imageUri: String,
        val stage: ProcessingStage,
        val phase: DetectionPhase,
        val phaseStartedElapsedRealtimeMs: Long,
        val estimatedPhaseDurationMs: Long?,
    ) : PipelineState

    data class WaitingForModels(
        val imageUri: String,
        val preparedImage: PreparedImage,
        val diagnostics: List<ModelDiagnostic>,
        val stageImages: List<StageOutputImage> = emptyList(),
        val condition: RegressionCondition? = null,
        val debugBundleUri: String? = null,
    ) : PipelineState

    data class PoseFallbackPrompt(
        val imageUri: String,
        val message: String,
    ) : PipelineState

    data class Success(
        val originalImageUri: String,
        val stageImages: List<StageOutputImage>,
        val preparedImage: PreparedImage,
        val poseConfidence: Float,
        val keypointConfidences: List<Float>,
        val candidateCount: Int,
        val candidateCountsByClass: Map<String, Int>,
        val rainbowGrid: RainbowGridSummary,
        val correctedColors: List<RainbowRegionColor>,
        val rainbowQualityFlags: List<String>,
        val prediction: FreshnessPrediction,
        val roiGains: Map<String, List<Double>>,
        val debugBundleUri: String?,
    ) : PipelineState

    data class Failure(
        val imageUri: String?,
        val message: String,
    ) : PipelineState
}

enum class ProcessingStage {
    NORMALIZING_ORIENTATION,
    RUNNING_PANCAKE,
    RECTIFYING,
    RUNNING_HYDRALISK,
    RUNNING_RAINBOW,
    RUNNING_REGRESSION,
}

fun PipelineState.originalImageUriOrNull(): String? = when (this) {
    PipelineState.Idle -> null
    is PipelineState.Ready -> imageUri
    is PipelineState.Processing -> imageUri
    is PipelineState.WaitingForModels -> preparedImage.originalUri
    is PipelineState.PoseFallbackPrompt -> imageUri
    is PipelineState.Success -> originalImageUri
    is PipelineState.Failure -> imageUri
}

/** Kept for existing callers; user-facing previews should prefer the original image. */
fun PipelineState.imageUriOrNull(): String? = originalImageUriOrNull()
