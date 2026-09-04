package dev.s2tmic.companion.network

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Records PCM chunks in memory and submits one WAV file to Groq Whisper. */
class GroqWhisperTranscriber(
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onCompleted(transcript: String)
        fun onFailure(message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val terminal = AtomicBoolean(false)
    private val audioLock = Any()
    private val pcmChunks = ArrayList<ByteArray>()
    private var pcmBytes = 0
    private var call: Call? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun appendAudio(pcm16At24Khz: ByteArray) {
        if (terminal.get() || pcm16At24Khz.isEmpty()) return
        var tooLong = false
        synchronized(audioLock) {
            if (terminal.get()) return
            if (pcmBytes + pcm16At24Khz.size <= MAX_PCM_BYTES) {
                pcmChunks += pcm16At24Khz
                pcmBytes += pcm16At24Khz.size
            } else {
                tooLong = true
            }
        }
        if (tooLong) fail("Die Aufnahme ist zu lang. Bitte kürzer diktieren.")
    }

    fun transcribe() {
        if (terminal.get()) return
        val pcm = synchronized(audioLock) {
            if (pcmBytes < MIN_AUDIO_BYTES) {
                null
            } else {
                ByteArray(pcmBytes).also { joined ->
                    var offset = 0
                    pcmChunks.forEach { chunk ->
                        chunk.copyInto(joined, offset)
                        offset += chunk.size
                    }
                    pcmChunks.clear()
                    pcmBytes = 0
                }
            }
        }
        if (pcm == null) {
            fail("Die Aufnahme war zu kurz. Bitte mindestens kurz sprechen.")
            return
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "dictation.wav",
                wavFromPcm16(pcm).toRequestBody(WAV_MEDIA_TYPE),
            )
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        call = client.newCall(request).also { requestCall ->
            requestCall.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    fail(error.localizedMessage ?: "Groq-Verbindung fehlgeschlagen")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val responseBody = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val apiMessage = runCatching {
                                JSONObject(responseBody).optJSONObject("error")?.optString("message")
                            }.getOrNull()
                            fail(apiMessage?.takeIf(String::isNotBlank) ?: httpError(it.code))
                            return
                        }
                        val transcript = runCatching {
                            JSONObject(responseBody).optString("text").trim()
                        }.getOrDefault("")
                        if (terminal.compareAndSet(false, true)) {
                            onMain { listener.onCompleted(transcript) }
                            client.dispatcher.executorService.shutdown()
                        }
                    }
                }
            })
        }
    }

    fun cancel() {
        if (terminal.compareAndSet(false, true)) {
            call?.cancel()
            synchronized(audioLock) {
                pcmChunks.clear()
                pcmBytes = 0
            }
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun fail(message: String) {
        if (terminal.compareAndSet(false, true)) {
            call?.cancel()
            onMain { listener.onFailure(message) }
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun httpError(code: Int): String = when (code) {
        401 -> "Groq API-Key wurde abgelehnt"
        413 -> "Die Aufnahme ist für Groq zu groß"
        429 -> "Groq-Limit erreicht. Bitte Rate-Limit und Guthaben prüfen."
        else -> "Groq-Fehler ($code)"
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private fun wavFromPcm16(pcm: ByteArray): ByteArray = ByteBuffer
        .allocate(WAV_HEADER_BYTES + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }
        .array()

    private companion object {
        const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val MODEL = "whisper-large-v3-turbo"
        const val SAMPLE_RATE = 24_000
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val WAV_HEADER_BYTES = 44
        const val MIN_AUDIO_BYTES = 4_800 // 100 ms
        const val MAX_PCM_BYTES = 14_400_000 // 5 minutes, safely below Groq's upload limit
        val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}
