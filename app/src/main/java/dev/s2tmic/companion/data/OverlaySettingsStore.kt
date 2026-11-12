package dev.s2tmic.companion.data

import android.content.Context
import dev.s2tmic.companion.accessibility.OverlayAnchor
import dev.s2tmic.companion.accessibility.OverlayOffsets

/** Persists where the user dragged the overlay, separately for portrait and landscape. */
class OverlaySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isPositionLocked(): Boolean = preferences.getBoolean(KEY_LOCKED, false)

    fun setPositionLocked(locked: Boolean) {
        preferences.edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    /** The dragged position relative to the keyboard, or null while the default position is in use. */
    fun loadAnchor(landscape: Boolean): OverlayAnchor? {
        val suffix = suffix(landscape)
        val rightKey = "${KEY_ANCHOR_RIGHT}_$suffix"
        val topKey = "${KEY_ANCHOR_TOP}_$suffix"
        if (!preferences.contains(rightKey) || !preferences.contains(topKey)) return null
        return OverlayAnchor(preferences.getInt(rightKey, 0), preferences.getInt(topKey, 0))
    }

    fun saveAnchor(landscape: Boolean, anchor: OverlayAnchor) {
        val suffix = suffix(landscape)
        preferences.edit()
            .putInt("${KEY_ANCHOR_RIGHT}_$suffix", anchor.fromRight)
            .putInt("${KEY_ANCHOR_TOP}_$suffix", anchor.aboveTop)
            .remove("${KEY_LEGACY_X}_$suffix")
            .remove("${KEY_LEGACY_Y}_$suffix")
            .apply()
    }

    /** Screen-relative window offsets saved by versions that did not anchor the overlay to the keyboard. */
    fun loadLegacyOffsets(landscape: Boolean): OverlayOffsets? {
        val suffix = suffix(landscape)
        val xKey = "${KEY_LEGACY_X}_$suffix"
        val yKey = "${KEY_LEGACY_Y}_$suffix"
        if (!preferences.contains(xKey) || !preferences.contains(yKey)) return null
        return OverlayOffsets(preferences.getInt(xKey, 0), preferences.getInt(yKey, 0))
    }

    fun clearPositions() {
        preferences.edit().apply {
            for (suffix in listOf(suffix(landscape = false), suffix(landscape = true))) {
                remove("${KEY_ANCHOR_RIGHT}_$suffix")
                remove("${KEY_ANCHOR_TOP}_$suffix")
                remove("${KEY_LEGACY_X}_$suffix")
                remove("${KEY_LEGACY_Y}_$suffix")
            }
            remove(KEY_LEGACY_RESET_REQUESTED)
        }.apply()
    }

    private fun suffix(landscape: Boolean): String = if (landscape) "landscape" else "portrait"

    private companion object {
        const val PREFERENCES = "overlay_settings"
        const val KEY_LOCKED = "position_locked"
        const val KEY_ANCHOR_RIGHT = "anchor_right"
        const val KEY_ANCHOR_TOP = "anchor_top"
        const val KEY_LEGACY_X = "position_x"
        const val KEY_LEGACY_Y = "position_y"
        const val KEY_LEGACY_RESET_REQUESTED = "position_reset_requested"
    }
}
