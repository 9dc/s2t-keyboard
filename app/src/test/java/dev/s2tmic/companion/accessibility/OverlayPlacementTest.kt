package dev.s2tmic.companion.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    private val keyboard = KeyboardFrame(topFromBottom = 900, rightInset = 0)

    @Test
    fun placesAnchorRelativeToKeyboardCorner() {
        val offsets = OverlayPlacement.offsets(OverlayAnchor(10, -120), keyboard, 1080, 2400, 132)
        assertEquals(OverlayOffsets(x = 10, y = 780), offsets)
    }

    @Test
    fun followsKeyboardHeightAndSideInset() {
        val taller = KeyboardFrame(topFromBottom = 1100, rightInset = 130)
        val offsets = OverlayPlacement.offsets(OverlayAnchor(10, -120), taller, 2400, 1080, 132)
        assertEquals(OverlayOffsets(x = 140, y = 948), offsets)
    }

    @Test
    fun keepsOverlayOnScreen() {
        val offsets = OverlayPlacement.offsets(OverlayAnchor(5000, 5000), keyboard, 1080, 2400, 132)
        assertEquals(OverlayOffsets(x = 948, y = 2268), offsets)
        assertEquals(
            OverlayOffsets(x = 0, y = 0),
            OverlayPlacement.offsets(OverlayAnchor(-50, -5000), keyboard, 1080, 2400, 132),
        )
    }

    @Test
    fun anchorRoundTripsThroughOffsets() {
        val anchor = OverlayAnchor(fromRight = 40, aboveTop = 300)
        val offsets = OverlayPlacement.offsets(anchor, keyboard, 1080, 2400, 132)
        assertEquals(anchor, OverlayPlacement.anchor(offsets, keyboard))
    }
}
