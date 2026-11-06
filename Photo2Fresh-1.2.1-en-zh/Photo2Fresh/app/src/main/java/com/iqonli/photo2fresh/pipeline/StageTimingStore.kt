package com.iqonli.photo2fresh.pipeline

import android.content.Context
import kotlin.math.roundToLong

enum class DetectionPhase {
    PREPARING_AND_POSE,
    RECTIFICATION,
    SEGMENTATION,
    RAINBOW,
    REGRESSION,
}

val allDetectionPhases: List<DetectionPhase> = DetectionPhase.entries

/** Persists the five most recent successful durations for each user-visible phase. */
class StageTimingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun estimatedDurationMs(phase: DetectionPhase): Long? {
        val values = read(phase)
        if (values.isEmpty()) return null
        return values.average().roundToLong().coerceAtLeast(MIN_DURATION_MS)
    }

    fun record(phase: DetectionPhase, durationMs: Long) {
        if (durationMs <= 0L) return
        val values = (read(phase) + durationMs.coerceAtMost(MAX_DURATION_MS)).takeLast(HISTORY_SIZE)
        preferences.edit().putString(key(phase), values.joinToString(",")).apply()
    }

    private fun read(phase: DetectionPhase): List<Long> = preferences
        .getString(key(phase), null)
        ?.split(',')
        ?.mapNotNull(String::toLongOrNull)
        ?.filter { it in 1L..MAX_DURATION_MS }
        ?.takeLast(HISTORY_SIZE)
        .orEmpty()

    private fun key(phase: DetectionPhase): String = "duration_${phase.name.lowercase()}"

    companion object {
        private const val PREFERENCES_NAME = "stage_timing_history_v2"
        private const val HISTORY_SIZE = 5
        private const val MIN_DURATION_MS = 100L
        private const val MAX_DURATION_MS = 10L * 60L * 1000L
    }
}
