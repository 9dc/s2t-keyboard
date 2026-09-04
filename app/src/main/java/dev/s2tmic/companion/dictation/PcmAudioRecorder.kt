package dev.s2tmic.companion.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Captures mono PCM16 and normalizes it to the 24 kHz required by Realtime. */
class PcmAudioRecorder(
    private val onAudio24Khz: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        check(running.compareAndSet(false, true)) { "Recorder is already running" }

        try {
            val sampleRate = SUPPORTED_SAMPLE_RATES.firstOrNull { rate ->
                AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ) > 0
            } ?: error("Kein unterstütztes Mikrofonformat gefunden")

            val minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = maxOf(minimum * 2, sampleRate / 5 * 2)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Mikrofon konnte nicht initialisiert werden"
            }
            audioRecord = recorder
            recorder.startRecording()

            recordingThread = thread(name = "s2t-audio-capture", isDaemon = true) {
                val buffer = ByteArray(bufferSize / 2)
                try {
                    while (running.get()) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            val pcm = buffer.copyOf(count - (count % 2))
                            onAudio24Khz(if (sampleRate == TARGET_SAMPLE_RATE) pcm else resamplePcm16(pcm, sampleRate))
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
            onFailure(error)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        recordingThread?.join(800)
        release()
    }

    private fun release() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordingThread = null
    }

    private fun resamplePcm16(input: ByteArray, sourceRate: Int): ByteArray {
        val inputSamples = input.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val outputSamples = (inputSamples.toLong() * TARGET_SAMPLE_RATE / sourceRate).toInt()
        val output = ByteArray(outputSamples * 2)
        for (index in 0 until outputSamples) {
            val sourceIndex = (index.toLong() * sourceRate / TARGET_SAMPLE_RATE)
                .toInt()
                .coerceAtMost(inputSamples - 1)
            output[index * 2] = input[sourceIndex * 2]
            output[index * 2 + 1] = input[sourceIndex * 2 + 1]
        }
        return output
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 24_000
        val SUPPORTED_SAMPLE_RATES = listOf(TARGET_SAMPLE_RATE, 48_000, 44_100, 16_000)
    }
}
