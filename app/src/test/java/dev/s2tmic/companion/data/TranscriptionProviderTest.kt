package dev.s2tmic.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionProviderTest {
    @Test
    fun resolvesKnownProviderIds() {
        assertEquals(TranscriptionProvider.Groq, TranscriptionProvider.fromId("groq"))
        assertEquals(TranscriptionProvider.OpenRouter, TranscriptionProvider.fromId("openrouter"))
    }

    @Test
    fun fallsBackToGroqForUnknownOrMissingIds() {
        assertEquals(TranscriptionProvider.Groq, TranscriptionProvider.fromId(null))
        assertEquals(TranscriptionProvider.Groq, TranscriptionProvider.fromId("does-not-exist"))
    }
}
