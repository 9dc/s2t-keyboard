package dev.s2tmic.companion.dictation

sealed interface DictationState {
    data object Idle : DictationState
    data object Listening : DictationState
    data object Finalizing : DictationState

    /** [canRetry] means the recording is still held and a tap uploads it again. */
    data class Failed(val message: String, val canRetry: Boolean = false) : DictationState
}
