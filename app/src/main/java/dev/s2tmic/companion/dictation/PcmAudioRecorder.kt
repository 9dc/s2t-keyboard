package dev.s2tmic.companion.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Captures mono PCM16 and delivers it at [PcmAudio.SAMPLE_RATE] in chunks of about 100 ms. */
class PcmAudioRecorder(
    private val onAudio: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    /** Starts capturing; the microphone is live when this returns. Throws if it cannot be opened. */
    @SuppressLint("MissingPermission")
    fun start() {
        check(running.compareAndSet(false, true)) { "Recorder is already running" }

        try {
            val sampleRate = SUPPORTED_SAMPLE_RATES.firstOrNull { rate -> minBufferSize(rate) > 0 }
                ?: error("Kein unterstütztes Mikrofonformat gefunden")
            val chunkBytes = sampleRate / CHUNKS_PER_SECOND * PcmAudio.BYTES_PER_SAMPLE
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize(sampleRate) * 2, chunkBytes * 2),
            )
            audioRecord = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Mikrofon konnte nicht initialisiert werden"
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Mikrofon ist gerade nicht verfügbar"
            }

            recordingThread = thread(name = "s2t-audio-capture", isDaemon = true) {
                val buffer = ByteArray(chunkBytes)
                try {
                    while (running.get()) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            onAudio(PcmAudio.resample(buffer.copyOf(count - (count % 2)), sampleRate))
                        } else if (count < 0) {
                            error("AudioRecord-Fehler $count")
                        }
                    }
                } catch (error: Throwable) {
                    if (running.getAndSet(false)) onFailure(error)
                }
            }
        } catch (error: Throwable) {
            running.set(false)
            release()
            throw error
        }
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        val thread = recordingThread
        if (thread != null && thread !== Thread.currentThread()) thread.join(800)
        release()
    }

    private fun release() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordingThread = null
    }

    private fun minBufferSize(sampleRate: Int): Int = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    private companion object {
        const val CHUNKS_PER_SECOND = 10
        val SUPPORTED_SAMPLE_RATES = listOf(PcmAudio.SAMPLE_RATE, 48_000, 44_100, 32_000)
    }
}
