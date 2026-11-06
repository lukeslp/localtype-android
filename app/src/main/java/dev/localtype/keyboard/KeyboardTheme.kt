package dev.localtype.keyboard

import android.content.Context
import android.content.res.Configuration

/** Light or dark for the keyboard and settings: follow the system, or always one of them. */
enum class ThemeMode { SYSTEM, LIGHT, DARK;

    /** [config] with night mode forced to this choice, or null when following the system. */
    fun apply(config: Configuration): Configuration? {
        if (this == SYSTEM) return null
        val night = if (this == DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == night) return null
        return Configuration(config).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
    }

    companion object {
        fun of(context: Context): ThemeMode =
            runCatching { valueOf(KeyboardSettings.theme(context)) }.getOrDefault(SYSTEM)
    }
}
