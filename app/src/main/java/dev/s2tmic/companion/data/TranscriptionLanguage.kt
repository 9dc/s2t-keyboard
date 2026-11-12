package dev.s2tmic.companion.data

/** A spoken language the user can pin; [code] is ISO-639-1 and blank for automatic detection. */
data class TranscriptionLanguage(val code: String, val label: String) {
    companion object {
        val Auto = TranscriptionLanguage("", "Automatisch erkennen")

        val all = listOf(
            Auto,
            TranscriptionLanguage("de", "Deutsch"),
            TranscriptionLanguage("en", "English"),
            TranscriptionLanguage("fr", "Français"),
            TranscriptionLanguage("es", "Español"),
            TranscriptionLanguage("it", "Italiano"),
            TranscriptionLanguage("nl", "Nederlands"),
            TranscriptionLanguage("pl", "Polski"),
            TranscriptionLanguage("pt", "Português"),
            TranscriptionLanguage("tr", "Türkçe"),
            TranscriptionLanguage("ru", "Русский"),
            TranscriptionLanguage("uk", "Українська"),
        )

        fun fromCode(code: String): TranscriptionLanguage = all.firstOrNull { it.code == code } ?: Auto
    }
}
