package dev.s2tmic.companion.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {
    @Test
    fun treatsExposedPlaceholderAsEmptyText() {
        assertEquals("", TextInsertion.editableText("Message", "Message", true))
        assertEquals("", TextInsertion.editableText("Message", "Message", false))
    }

    @Test
    fun keepsRealEditorText() {
        assertEquals("Hallo", TextInsertion.editableText("Hallo", "Message", false))
    }

    @Test
    fun insertsAtCursorWithNaturalSpacing() {
        val result = TextInsertion.atSelection("HalloWelt", 5, 5, "schöne")
        assertEquals("Hallo schöne Welt", result.text)
        assertEquals(13, result.cursor)
    }

    @Test
    fun replacesSelection() {
        val result = TextInsertion.atSelection("Hallo alte Welt", 6, 10, "neue")
        assertEquals("Hallo neue Welt", result.text)
        assertEquals(10, result.cursor)
    }

    @Test
    fun doesNotPutSpaceBeforePunctuation() {
        val result = TextInsertion.atSelection("Hallo", 5, 5, ".")
        assertEquals("Hallo.", result.text)
        assertEquals(6, result.cursor)
    }
}
