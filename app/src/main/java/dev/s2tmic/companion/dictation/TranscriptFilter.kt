package dev.s2tmic.companion.dictation

/** Drops the subtitle credits Whisper models invent when a recording contains no speech. */
object TranscriptFilter {
    /** Returns the trimmed transcript, or an empty string when it is only such an invented credit. */
    fun clean(transcript: String): String {
        val text = transcript.trim()
        val key = text.lowercase().trimEnd('.', '!', ' ')
        return if (key.length <= MAX_CREDIT_LENGTH && credits.any { it.matches(key) }) "" else text
    }

    private const val MAX_CREDIT_LENGTH = 80

    private val credits = listOf(
        Regex("untertitel(ung)?\\b.*\\b(amara|zdf|funk|ard|wdr|swr|ndr)\\b.*"),
        Regex("untertitel von .*"),
        Regex("subtitles? (by|from) .*"),
        Regex("(transcribed|transcription|captions?) by .*"),
        Regex(".*amara\\.org.*"),
        Regex("copyright (wdr|swr|ndr|zdf|ard)\\b.*"),
        Regex("(wdr|swr|ndr) \\d{4}"),
    )
}
