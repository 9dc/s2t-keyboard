package dev.s2tmic.companion.data

/** Selectable speech-to-text backends. All use the OpenAI-compatible transcription API. */
enum class TranscriptionProvider(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val defaultModel: String,
) {
    Groq(
        id = "groq",
        displayName = "Groq",
        endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
        defaultModel = "whisper-large-v3-turbo",
    ),
    OpenRouter(
        id = "openrouter",
        displayName = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/audio/transcriptions",
        defaultModel = "microsoft/mai-transcribe-2",
    ),
    ;

    /** Only OpenRouter exposes a queryable list of speech-to-text models. */
    val hasSelectableModels: Boolean get() = this == OpenRouter

    companion object {
        /** Defaults to [Groq] for unknown or missing values (also keeps legacy installs working). */
        fun fromId(id: String?): TranscriptionProvider =
            entries.firstOrNull { it.id == id } ?: Groq
    }
}
