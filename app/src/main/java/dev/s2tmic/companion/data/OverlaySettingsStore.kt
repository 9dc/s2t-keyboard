package dev.s2tmic.companion.data

import android.content.Context
import android.content.res.Configuration

data class OverlayPosition(val x: Int, val y: Int)

class OverlaySettingsStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isPositionLocked(): Boolean = preferences.getBoolean(KEY_LOCKED, false)

    fun setPositionLocked(locked: Boolean) {
        preferences.edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    fun loadPosition(): OverlayPosition? {
        val suffix = orientationSuffix()
        val xKey = "${KEY_X}_$suffix"
        val yKey = "${KEY_Y}_$suffix"
        if (!preferences.contains(xKey) || !preferences.contains(yKey)) return null
        return OverlayPosition(preferences.getInt(xKey, 0), preferences.getInt(yKey, 0))
    }

    fun savePosition(x: Int, y: Int) {
        val suffix = orientationSuffix()
        preferences.edit()
            .putInt("${KEY_X}_$suffix", x)
            .putInt("${KEY_Y}_$suffix", y)
            .apply()
    }

    fun clearPositions() {
        preferences.edit()
            .remove("${KEY_X}_portrait")
            .remove("${KEY_Y}_portrait")
            .remove("${KEY_X}_landscape")
            .remove("${KEY_Y}_landscape")
            .apply()
    }

    private fun orientationSuffix(): String =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }

    private companion object {
        const val PREFERENCES = "overlay_settings"
        const val KEY_LOCKED = "position_locked"
        const val KEY_X = "position_x"
        const val KEY_Y = "position_y"
    }
}
