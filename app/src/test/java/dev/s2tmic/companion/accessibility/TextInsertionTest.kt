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
    fun treatsContentDescriptionPlaceholderAsEmptyText() {
        assertEquals(
            "",
            TextInsertion.editableText("Message", null, false, contentDescription = "Message"),
        )
    }

    @Test
    fun keepsRealTextWhenContentDescriptionDiffers() {
        assertEquals(
            "Hallo",
            TextInsertion.editableText("Hallo", null, false, contentDescription = "Message"),
        )
    }

    @Test
    fun treatsStandalonePlaceholderAsEmptyText() {
        assertEquals("", TextInsertion.editableText("Nachricht", null, false))
        assertEquals(
            "",
            TextInsertion.editableText(
                "Message",
                null,
                false,
                className = "android.widget.EditText",
                selectionStart = 7,
                selectionEnd = 7,
            ),
        )
    }

    @Test
    fun treatsWebEditorPlaceholderAsEmptyText() {
        assertEquals(
            "",
            TextInsertion.editableText(
                "Message",
                null,
                false,
                className = "android.webkit.WebView",
                packageName = "com.android.chrome",
                selectionStart = 7,
                selectionEnd = 7,
            ),
        )
        assertEquals(
            "",
            TextInsertion.editableText(
                "Write your message",
                null,
                false,
                className = "android.webkit.WebView",
                packageName = "com.android.chrome",
                selectionStart = 18,
                selectionEnd = 18,
            ),
        )
    }

    @Test
    fun keepsRealWebEditorText() {
        assertEquals(
            "Nachricht an Anna",
            TextInsertion.editableText(
                "Nachricht an Anna",
                null,
                false,
                className = "android.webkit.WebView",
                packageName = "com.android.chrome",
                selectionStart = 17,
                selectionEnd = 17,
            ),
        )
    }

    @Test
    fun normalizesPlaceholderWhitespace() {
        assertEquals("", TextInsertion.editableText("Message\u00A0", "\u200BMessage", false))
    }

    @Test
    fun treatsPlaceholderWithEllipsisAsEmptyText() {
        assertEquals("", TextInsertion.editableText("Nachricht\u2026", null, false))
        assertEquals(
            "",
            TextInsertion.editableText(
                "Type a message...",
                "Type a message",
                false,
            ),
        )
    }

    @Test
    fun treatsEmptyHtmlMarkupAsEmptyText() {
        assertEquals("", TextInsertion.editableText("<div><br></div>", null, false))
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

    @Test
    fun preparesNaturalSpacingForDirectPaste() {
        assertEquals(" schöne ", TextInsertion.forPaste("HalloWelt", 5, 5, "schöne"))
        assertEquals("Hallo", TextInsertion.forPaste("", 0, 0, " Hallo "))
    }
}
