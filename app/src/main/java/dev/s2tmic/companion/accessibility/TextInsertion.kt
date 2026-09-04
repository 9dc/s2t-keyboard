package dev.s2tmic.companion.accessibility

data class TextInsertionResult(val text: String, val cursor: Int)

object TextInsertion {
    fun atSelection(
        currentText: String,
        selectionStart: Int,
        selectionEnd: Int,
        transcript: String,
    ): TextInsertionResult {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, currentText.length)
        val spoken = transcript.trim()

        val needsLeadingSpace = start > 0 &&
            spoken.isNotEmpty() &&
            !currentText[start - 1].isWhitespace() &&
            !spoken.first().isPunctuation()
        val needsTrailingSpace = end < currentText.length &&
            spoken.isNotEmpty() &&
            !currentText[end].isWhitespace() &&
            !currentText[end].isPunctuation() &&
            !spoken.last().isWhitespace()

        val insertion = buildString {
            if (needsLeadingSpace) append(' ')
            append(spoken)
            if (needsTrailingSpace) append(' ')
        }
        return TextInsertionResult(
            text = currentText.replaceRange(start, end, insertion),
            cursor = start + insertion.length,
        )
    }

    private fun Char.isPunctuation(): Boolean = this in ".,;:!?)]}»”’"
}

