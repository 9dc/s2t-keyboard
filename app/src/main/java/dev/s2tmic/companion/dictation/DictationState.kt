package dev.s2tmic.companion.dictation

sealed interface DictationState {
    data object Idle : DictationState
    data object Connecting : DictationState
    data class Listening(val partialText: String) : DictationState
    data class Finalizing(val partialText: String) : DictationState
    data class Failed(val message: String) : DictationState
}

