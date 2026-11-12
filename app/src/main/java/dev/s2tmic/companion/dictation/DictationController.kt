package dev.s2tmic.companion.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.s2tmic.companion.data.ApiKeyStore
import dev.s2tmic.companion.data.TranscriptionSettingsStore
import dev.s2tmic.companion.network.TranscriptionApi
import dev.s2tmic.companion.network.TranscriptionRequest
import dev.s2tmic.companion.network.TranscriptionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import java.util.concurrent.atomic.AtomicBoolean

/** Runs one dictation at a time: record, upload, hand over the transcript. Main thread only. */
class DictationController(
    private val context: Context,
    private val keyStore: ApiKeyStore,
    private val transcriptionSettings: TranscriptionSettingsStore,
    private val onFinalTranscript: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearFailure = Runnable {
        if (mutableState.value is DictationState.Failed) {
            retryRequest = null
            mutableState.value = DictationState.Idle
        }
    }

    /** Bumped whenever the current dictation ends, so callbacks that belong to an older one are dropped. */
    private var generation = 0
    private var recording: Recording? = null
    private var upload: Call? = null
    private var retryRequest: TranscriptionRequest? = null

    private class Recording(
        val recorder: PcmAudioRecorder,
        val buffer: PcmBuffer,
        val request: (RecordedAudio) -> TranscriptionRequest,
    )

    fun toggle() {
        when (val current = mutableState.value) {
            DictationState.Idle -> start()
            is DictationState.Failed -> if (current.canRetry) retry() else start()
            DictationState.Listening -> stopAndCommit()
            DictationState.Finalizing -> Unit
        }
    }

    fun start() {
        val current = mutableState.value
        if (current == DictationState.Listening || current == DictationState.Finalizing) return
        mainHandler.removeCallbacks(clearFailure)
        retryRequest = null

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Mikrofonfreigabe fehlt. Bitte die S2T-Mic-App öffnen.")
            return
        }
        val provider = transcriptionSettings.selectedProvider()
        val apiKey = keyStore.load(provider)
        if (apiKey.isNullOrBlank()) {
            fail("${provider.displayName} API-Key fehlt. Bitte die S2T-Mic-App öffnen.")
            return
        }
        val model = transcriptionSettings.modelFor(provider)
        val language = transcriptionSettings.language()

        val generation = ++generation
        val buffer = PcmBuffer(MAX_PCM_BYTES)
        val full = AtomicBoolean(false)
        val recorder = PcmAudioRecorder(
            onAudio = { chunk ->
                // A full buffer ends the dictation but keeps what was said so far.
                if (!buffer.append(chunk) && full.compareAndSet(false, true)) {
                    mainHandler.post { if (generation == this.generation) stopAndCommit() }
                }
            },
            onFailure = { error ->
                mainHandler.post {
                    if (generation == this.generation) fail(error.localizedMessage ?: "Mikrofonfehler")
                }
            },
        )
        try {
            recorder.start()
        } catch (error: Throwable) {
            fail(error.localizedMessage ?: "Mikrofonfehler")
            return
        }
        recording = Recording(recorder, buffer) { audio ->
            TranscriptionRequest(provider, model, apiKey, language, audio)
        }
        // Only now is the microphone live, so the button turns red exactly when speaking is captured.
        mutableState.value = DictationState.Listening
        TranscriptionApi.warmUp(provider)
    }

    fun stopAndCommit() {
        if (mutableState.value != DictationState.Listening) return
        mutableState.value = DictationState.Finalizing
        // Keep recording briefly: the stop tap usually lands on the last syllable.
        val generation = generation
        mainHandler.postDelayed({ if (generation == this.generation) finishRecording() }, TAIL_MS)
    }

    /** Drops the running dictation, a pending upload and any recording kept for a retry. */
    fun cancel() {
        endCurrentWork()
        retryRequest = null
        mainHandler.removeCallbacks(clearFailure)
        mutableState.value = DictationState.Idle
    }

    private fun finishRecording() {
        val finished = recording ?: return
        recording = null
        finished.recorder.stop()
        val audio = finished.buffer.snapshot()
        when {
            audio.durationMs < MIN_AUDIO_MS -> fail("Die Aufnahme war zu kurz.")
            audio.isSilent -> fail("Das Mikrofon liefert kein Signal. Ist es stummgeschaltet oder von einer anderen App belegt?")
            else -> upload(finished.request(audio))
        }
    }

    private fun retry() {
        val request = retryRequest ?: return start()
        retryRequest = null
        mainHandler.removeCallbacks(clearFailure)
        upload(request)
    }

    private fun upload(request: TranscriptionRequest) {
        val generation = ++generation
        mutableState.value = DictationState.Finalizing
        upload = TranscriptionApi.transcribe(request) { result ->
            mainHandler.post {
                if (generation != this.generation) return@post
                upload = null
                when (result) {
                    is TranscriptionResult.Success -> {
                        val transcript = TranscriptFilter.clean(result.transcript)
                        if (transcript.isEmpty()) {
                            fail("Kein gesprochener Text erkannt")
                        } else {
                            mutableState.value = DictationState.Idle
                            onFinalTranscript(transcript)
                        }
                    }

                    is TranscriptionResult.Failure -> if (result.retryable) {
                        retryRequest = request
                        fail("${result.message} · Tippen zum Wiederholen", canRetry = true)
                    } else {
                        fail(result.message)
                    }
                }
            }
        }
    }

    private fun fail(message: String, canRetry: Boolean = false) {
        endCurrentWork()
        if (!canRetry) retryRequest = null
        mutableState.value = DictationState.Failed(message, canRetry)
        mainHandler.removeCallbacks(clearFailure)
        mainHandler.postDelayed(clearFailure, if (canRetry) RETRY_WINDOW_MS else FAILURE_VISIBLE_MS)
    }

    private fun endCurrentWork() {
        generation++
        recording?.recorder?.stop()
        recording = null
        upload?.cancel()
        upload = null
    }

    private companion object {
        const val TAIL_MS = 200L
        const val MIN_AUDIO_MS = 400L
        const val FAILURE_VISIBLE_MS = 4_000L
        const val RETRY_WINDOW_MS = 15_000L
        const val MAX_PCM_BYTES = 5 * 60 * PcmAudio.BYTES_PER_SECOND // 9.6 MB, well below the 25 MB upload limit
    }
}
