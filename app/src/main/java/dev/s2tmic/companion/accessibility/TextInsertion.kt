package dev.s2tmic.companion.accessibility

data class TextInsertionResult(val text: String, val cursor: Int)

object TextInsertion {
    fun editableText(
        exposedText: CharSequence?,
        hintText: CharSequence?,
        isShowingHintText: Boolean,
        contentDescription: CharSequence? = null,
        className: CharSequence? = null,
        packageName: CharSequence? = null,
        selectionStart: Int = -1,
        selectionEnd: Int = -1,
    ): String {
        val raw = exposedText?.toString().orEmpty()
        val text = raw.normalizedText()
        if (text.isEmpty() || text.isHtmlPlaceholder()) return ""

        val hint = hintText.normalizedText()
        if (isShowingHintText || (hint.isNotEmpty() && text.placeholderKey() == hint.placeholderKey())) return ""

        val description = contentDescription.normalizedText()
        if (description.isNotEmpty() && text.placeholderKey() == description.placeholderKey()) return ""

        if (knownPlaceholders.any { text.placeholderKey() == it }) return ""

        // An unknown selection (-1) says nothing about the caret, so it must not loosen the check.
        val caretAtStart = selectionStart in 0..1 && selectionEnd in 0..1
        if (isWebLike(packageName, className) && looksLikeWebPlaceholder(text, strict = !caretAtStart)) return ""

        return raw
    }

    fun atSelection(
        currentText: String,
        selectionStart: Int,
        selectionEnd: Int,
        transcript: String,
    ): TextInsertionResult {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, currentText.length)
        val insertion = forPaste(currentText, start, end, transcript)

        return TextInsertionResult(
            text = currentText.replaceRange(start, end, insertion),
            cursor = start + insertion.length,
        )
    }

    fun forPaste(
        currentText: String,
        selectionStart: Int,
        selectionEnd: Int,
        transcript: String,
    ): String {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, currentText.length)
        return withSpacing(currentText.getOrNull(start - 1), currentText.getOrNull(end), transcript)
    }

    /**
     * The trimmed transcript with the spaces it needs to sit between [before] and [after], the
     * characters around the cursor or selection (null at the start or end of the text).
     */
    fun withSpacing(before: Char?, after: Char?, transcript: String): String {
        val spoken = transcript.trim()
        if (spoken.isEmpty()) return spoken

        val needsLeadingSpace = before != null &&
            !before.isWhitespace() &&
            !before.isOpening() &&
            !spoken.first().isPunctuation()
        val needsTrailingSpace = after != null &&
            !after.isWhitespace() &&
            !after.isPunctuation()

        return buildString {
            if (needsLeadingSpace) append(' ')
            append(spoken)
            if (needsTrailingSpace) append(' ')
        }
    }

    private fun Char.isPunctuation(): Boolean = this in ".,;:!?)]}»”’"

    private fun Char.isOpening(): Boolean = this in "([{„«‚"

    private fun CharSequence?.normalizedText(): String = this?.toString().orEmpty()
        .replace('\u00A0', ' ')
        .replace("\u200B", "")
        .replace("\uFEFF", "")
        .trim()

    private fun String.isHtmlPlaceholder(): Boolean {
        val compact = lowercase().replace("\\s+".toRegex(), "")
        return compact == "<br>" ||
            compact == "<br/>" ||
            compact == "<div><br></div>" ||
            compact == "<p><br></p>"
    }

    private fun isWebLike(packageName: CharSequence?, className: CharSequence?): Boolean {
        val pkg = packageName?.toString()?.lowercase().orEmpty()
        val cls = className?.toString()?.lowercase().orEmpty()
        return pkg.contains("chrome") ||
            pkg.contains("browser") ||
            pkg.contains("webview") ||
            pkg.contains("firefox") ||
            pkg.contains("mozilla") ||
            cls.contains("webview") ||
            cls.contains("webkit") ||
            cls.contains("gecko")
    }

    private fun looksLikeWebPlaceholder(text: String, strict: Boolean): Boolean {
        if (text.length > MAX_PLACEHOLDER_LENGTH) return false
        val key = text.placeholderKey()
        val prefixes = if (strict) strictPlaceholderPrefixes else loosePlaceholderPrefixes
        return prefixes.any { key.startsWith(it) }
    }

    private fun String.placeholderKey(): String = lowercase().trimEnd(' ', '.', '\u2026', '!')

    private const val MAX_PLACEHOLDER_LENGTH = 140

    private val knownPlaceholders = listOf(
        "message",
        "nachricht",
        "type a message",
        "write a message",
        "schreibe eine nachricht",
        "schreibe deine nachricht",
        "nachricht schreiben",
        "search",
        "suche",
        "text eingeben",
    )

    private val strictPlaceholderPrefixes = listOf(
        "write your message",
        "enter your message",
        "type your message",
        "nachricht schreiben",
        "schreibe eine nachricht",
        "schreibe deine nachricht",
    )

    private val loosePlaceholderPrefixes = strictPlaceholderPrefixes + listOf(
        "write ",
        "enter ",
        "type ",
        "search ",
        "send a message",
        "schreibe ",
        "schreib ",
        "tippe ",
        "gib ",
        "geben sie ",
        "suche ",
        "suchbegriff",
        "verfasse ",
        "eingeben",
    )
}
