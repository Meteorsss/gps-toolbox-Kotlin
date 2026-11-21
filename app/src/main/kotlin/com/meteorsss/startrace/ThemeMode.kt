package com.meteorsss.startrace

/** Explicit modes rather than persisting the current system appearance.  */
enum class ThemeMode(val label: String) {
    DARK("手动深色"),
    LIGHT("手动浅色"),
    SYSTEM("跟随系统");

    fun isLight(systemDark: Boolean): Boolean {
        return this == LIGHT || (this == SYSTEM && !systemDark)
    }

    companion object {
        fun restore(value: String, hasLegacy: Boolean, legacyLight: Boolean): ThemeMode {
            if (value != null) {
                try {
                    return valueOf(value)
                } catch (ignored: IllegalArgumentException) {
                }
            }
            return if (hasLegacy) (if (legacyLight) LIGHT else DARK) else SYSTEM
        }
    }
}
