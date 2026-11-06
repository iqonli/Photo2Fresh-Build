package com.iqonli.photo2fresh.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class UiLanguage { ENGLISH, CHINESE }

/** Process-memory setting, deliberately never persisted in preferences or saved-state. */
object AppLanguage {
    var current by mutableStateOf(UiLanguage.ENGLISH)
        private set

    fun select(language: UiLanguage) { current = language }
    fun reset() { current = UiLanguage.ENGLISH }
    fun text(source: String): String = DisplayTextCatalog.text(source, current == UiLanguage.ENGLISH)
}
