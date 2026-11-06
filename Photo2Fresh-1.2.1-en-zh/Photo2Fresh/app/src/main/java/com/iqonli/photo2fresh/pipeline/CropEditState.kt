package com.iqonli.photo2fresh.pipeline

data class CropEditState(
    val originalUri: String,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val rotationDegrees: Float = 0f,
    val appliedUri: String? = null,
    val isApplying: Boolean = false,
    val errorMessage: String? = null,
) {
    fun normalized(): CropEditState {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        val orderedLeft = minOf(safeLeft, safeRight)
        val orderedRight = maxOf(safeLeft, safeRight)
        val orderedTop = minOf(safeTop, safeBottom)
        val orderedBottom = maxOf(safeTop, safeBottom)
        return copy(
            left = orderedLeft,
            top = orderedTop,
            right = orderedRight,
            bottom = orderedBottom,
            rotationDegrees = rotationDegrees.coerceIn(MIN_ROTATION_DEGREES, MAX_ROTATION_DEGREES),
        )
    }

    companion object {
        const val MIN_ROTATION_DEGREES = -45f
        const val MAX_ROTATION_DEGREES = 45f
    }
}
