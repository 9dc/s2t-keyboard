package dev.s2tmic.companion.accessibility

/** Where the keyboard sits, as the distances of its top and right edge from the screen's bottom and right. */
data class KeyboardFrame(val topFromBottom: Int, val rightInset: Int)

/**
 * Overlay position relative to the keyboard's top-right corner, in px: [fromRight] grows to the left,
 * [aboveTop] grows upwards and is negative while the overlay sits inside the keyboard.
 */
data class OverlayAnchor(val fromRight: Int, val aboveTop: Int)

/** Window offsets for a bottom-right anchored window: [x] from the right edge, [y] from the bottom edge. */
data class OverlayOffsets(val x: Int, val y: Int)

/** Converts between a keyboard-relative [OverlayAnchor] and the window offsets used to show the overlay. */
object OverlayPlacement {
    fun offsets(
        anchor: OverlayAnchor,
        keyboard: KeyboardFrame,
        screenWidth: Int,
        screenHeight: Int,
        overlaySize: Int,
    ): OverlayOffsets = OverlayOffsets(
        x = (keyboard.rightInset + anchor.fromRight).coerceIn(0, (screenWidth - overlaySize).coerceAtLeast(0)),
        y = (keyboard.topFromBottom + anchor.aboveTop).coerceIn(0, (screenHeight - overlaySize).coerceAtLeast(0)),
    )

    fun anchor(offsets: OverlayOffsets, keyboard: KeyboardFrame): OverlayAnchor = OverlayAnchor(
        fromRight = offsets.x - keyboard.rightInset,
        aboveTop = offsets.y - keyboard.topFromBottom,
    )
}
