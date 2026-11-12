package dev.s2tmic.companion.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFilterTest {
    @Test
    fun dropsInventedSubtitleCredits() {
        assertEquals("", TranscriptFilter.clean(" Untertitel der Amara.org-Community"))
        assertEquals("", TranscriptFilter.clean("Untertitelung des ZDF, 2020"))
        assertEquals("", TranscriptFilter.clean("Untertitel im Auftrag des ZDF für funk, 2017"))
        assertEquals("", TranscriptFilter.clean("Subtitles by the Amara.org community"))
        assertEquals("", TranscriptFilter.clean("Copyright WDR 2021"))
    }

    @Test
    fun keepsOrdinarySentences() {
        assertEquals("Vielen Dank.", TranscriptFilter.clean(" Vielen Dank. "))
        assertEquals("Thank you.", TranscriptFilter.clean("Thank you."))
        assertEquals(
            "Die Untertitel im Video fehlen noch.",
            TranscriptFilter.clean("Die Untertitel im Video fehlen noch."),
        )
        assertEquals(
            "Der WDR sendet das morgen.",
            TranscriptFilter.clean("Der WDR sendet das morgen."),
        )
    }
}
