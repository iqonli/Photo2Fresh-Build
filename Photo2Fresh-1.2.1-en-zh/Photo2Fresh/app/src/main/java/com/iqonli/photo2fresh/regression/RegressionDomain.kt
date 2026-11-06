package com.iqonli.photo2fresh.regression

import java.util.Locale

enum class MeatType(val key: String, val label: String) {
    PORK("pork", "猪肉"), BEEF("beef", "牛肉"), CHICKEN("chicken", "鸡肉"),
}

enum class StorageTemperature(val celsius: Int, val label: String) {
    ROOM(25, "25℃"), CHILLED(4, "4℃"),
}

data class RegressionCondition(val meat: MeatType, val temperature: StorageTemperature) {
    val key: String get() = "${meat.key}_${temperature.celsius}c"
    val label: String get() = "${meat.label} · ${temperature.label}"
}

data class FreshnessPrediction(
    val condition: RegressionCondition,
    val tvbn: Float,
    val timeHours: Float,
    val modelVersion: String,
) {
    init {
        require(tvbn.isFinite() && timeHours.isFinite() && timeHours >= 0f) { "回归输出无效" }
    }
    // Classification uses the unrounded model output. Presentation never rescales TVB-N.
    val freshness: String get() = when {
        tvbn < 15f -> "新鲜"
        tvbn <= 20f -> "次新鲜"
        else -> "腐败"
    }
    val displayedTime: Float get() = if (condition.temperature == StorageTemperature.CHILLED) timeHours / 24f else timeHours
    val timeUnit: String get() = if (condition.temperature == StorageTemperature.CHILLED) "天" else "小时"
    fun timeText(): String = String.format(Locale.ROOT, "%.2f %s", displayedTime, timeUnit)
}
