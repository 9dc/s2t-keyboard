package dev.s2tmic.companion.data

import android.content.Context

/** Persists the selected provider, the spoken language and, where supported, the model per provider. */
class TranscriptionSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectedProvider(): TranscriptionProvider =
        TranscriptionProvider.fromId(preferences.getString(KEY_PROVIDER, null))

    fun setSelectedProvider(provider: TranscriptionProvider) {
        preferences.edit().putString(KEY_PROVIDER, provider.id).apply()
    }

    /** Stored model for [provider], or its built-in default when nothing was chosen. */
    fun modelFor(provider: TranscriptionProvider): String =
        preferences.getString(modelKey(provider), null)
            ?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel

    fun setModel(provider: TranscriptionProvider, model: String) {
        if (model.isBlank()) {
            preferences.edit().remove(modelKey(provider)).apply()
        } else {
            preferences.edit().putString(modelKey(provider), model).apply()
        }
    }

    /** ISO-639-1 code of the spoken language, or blank to let the model detect it. */
    fun language(): String = preferences.getString(KEY_LANGUAGE, null).orEmpty()

    fun setLanguage(code: String) {
        preferences.edit().putString(KEY_LANGUAGE, code).apply()
    }

    private fun modelKey(provider: TranscriptionProvider) = "${provider.id}_model"

    private companion object {
        const val PREFERENCES = "transcription_settings"
        const val KEY_PROVIDER = "provider"
        const val KEY_LANGUAGE = "language"
    }
}
