package dev.s2tmic.companion.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.s2tmic.companion.data.ApiKeyStore
import dev.s2tmic.companion.network.OpenAiRealtimeTranscriber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DictationController(
    private val context: Context,
    private val keyStore: ApiKeyStore,
    private val onFinalTranscript: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var transcriber: OpenAiRealtimeTranscriber? = null
    private var recorder: PcmAudioRecorder? = null
    private val partial = StringBuilder()

    fun toggle() {
        when (mutableState.value) {
            DictationState.Idle, is DictationState.Failed -> start()
            DictationState.Connecting, is DictationState.Listening -> stopAndCommit()
            is DictationState.Finalizing -> Unit
        }
    }

    fun start() {
        if (mutableState.value !is DictationState.Idle && mutableState.value !is DictationState.Failed) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Mikrofonfreigabe fehlt. Bitte die S2T-Mic-App öffnen.")
            return
        }
        val apiKey = keyStore.load()
        if (apiKey.isNullOrBlank()) {
            fail("OpenAI API-Key fehlt. Bitte die S2T-Mic-App öffnen.")
            return
        }

        partial.clear()
        mutableState.value = DictationState.Connecting
        val client = OpenAiRealtimeTranscriber(apiKey, object : OpenAiRealtimeTranscriber.Listener {
            override fun onReady() = onMain {
                if (mutableState.value != DictationState.Connecting) return@onMain
                val audioRecorder = PcmAudioRecorder(
                    onAudio24Khz = { transcriber?.appendAudio(it) },
                    onFailure = { fail(it.localizedMessage ?: "Mikrofonfehler") },
                )
                recorder = audioRecorder
                mutableState.value = DictationState.Listening("")
                audioRecorder.start()
            }

            override fun onDelta(delta: String) = onMain {
                partial.append(delta)
                if (mutableState.value is DictationState.Finalizing) {
                    mutableState.value = DictationState.Finalizing(partial.toString())
                } else {
                    mutableState.value = DictationState.Listening(partial.toString())
                }
            }

            override fun onCompleted(transcript: String) = onMain {
                recorder?.stop()
                recorder = null
                transcriber = null
                if (transcript.isNotBlank()) onFinalTranscript(transcript)
                mutableState.value = if (transcript.isBlank()) {
                    DictationState.Failed("Kein gesprochener Text erkannt")
                } else {
                    DictationState.Idle
                }
            }

            override fun onFailure(message: String) = fail(message)
        })
        transcriber = client
        client.connect()
    }

    fun stopAndCommit() {
        when (mutableState.value) {
            DictationState.Connecting -> cancel()
            is DictationState.Listening -> {
                recorder?.stop()
                recorder = null
                mutableState.value = DictationState.Finalizing(partial.toString())
                transcriber?.commit()
            }

            else -> Unit
        }
    }

    fun cancel() {
        recorder?.stop()
        recorder = null
        transcriber?.cancel()
        transcriber = null
        partial.clear()
        mutableState.value = DictationState.Idle
    }

    private fun fail(message: String) = onMain {
        recorder?.stop()
        recorder = null
        transcriber?.cancel()
        transcriber = null
        mutableState.value = DictationState.Failed(message)
        mainHandler.postDelayed({
            if (mutableState.value is DictationState.Failed) mutableState.value = DictationState.Idle
        }, 4_000)
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
