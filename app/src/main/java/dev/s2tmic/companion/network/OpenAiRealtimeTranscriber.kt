package dev.s2tmic.companion.network

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Minimal client for OpenAI's GA Realtime transcription event protocol. */
class OpenAiRealtimeTranscriber(
    private val apiKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onDelta(delta: String)
        fun onCompleted(transcript: String)
        fun onFailure(message: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val terminal = AtomicBoolean(false)
    private val configured = AtomicBoolean(false)
    private val sentAudioBytes = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$REALTIME_SESSION_MODEL")
            .header("Authorization", "Bearer $apiKey")
            .build()
        webSocket = client.newWebSocket(request, SocketListener())
    }

    fun appendAudio(pcm16At24Khz: ByteArray) {
        if (!configured.get() || terminal.get() || pcm16At24Khz.isEmpty()) return
        val event = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm16At24Khz, Base64.NO_WRAP))
        if (webSocket?.send(event.toString()) == true) {
            sentAudioBytes.addAndGet(pcm16At24Khz.size.toLong())
        }
    }

    fun commit() {
        if (terminal.get()) return
        if (sentAudioBytes.get() < MIN_AUDIO_BYTES) {
            fail("Die Aufnahme war zu kurz. Bitte mindestens kurz sprechen.")
            return
        }
        webSocket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
        mainHandler.postDelayed({
            if (!terminal.get()) fail("Zeitüberschreitung beim finalen Transkript")
        }, FINAL_TIMEOUT_MS)
    }

    fun cancel() {
        if (terminal.compareAndSet(false, true)) {
            webSocket?.close(1000, "cancelled")
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun sendSessionConfiguration(socket: WebSocket) {
        val transcription = JSONObject()
            .put("model", TRANSCRIPTION_MODEL)
            .put("languages", org.json.JSONArray(listOf("de", "en")))
            .put("delay", "low")
            .put(
                "prompt",
                "Natürliches Diktat in Deutsch oder Englisch. Behalte Satzzeichen, Großschreibung und Sprachwechsel bei.",
            )
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
            .put("transcription", transcription)
            .put("turn_detection", JSONObject.NULL)
        val session = JSONObject()
            .put("type", "transcription")
            .put("audio", JSONObject().put("input", input))
        socket.send(
            JSONObject()
                .put("type", "session.update")
                .put("session", session)
                .toString(),
        )
    }

    private fun handleMessage(text: String) {
        val event = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (event.optString("type")) {
            "session.updated", "transcription_session.updated" -> {
                if (configured.compareAndSet(false, true)) listener.onReady()
            }

            "conversation.item.input_audio_transcription.delta" -> {
                event.optString("delta").takeIf(String::isNotEmpty)?.let(listener::onDelta)
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = event.optString("transcript").trim()
                if (terminal.compareAndSet(false, true)) {
                    listener.onCompleted(transcript)
                    webSocket?.close(1000, "completed")
                    client.dispatcher.executorService.shutdown()
                }
            }

            "error" -> {
                val error = event.optJSONObject("error")
                fail(error?.optString("message").orEmpty().ifBlank { "Unbekannter OpenAI-Fehler" })
            }
        }
    }

    private fun fail(message: String) {
        if (terminal.compareAndSet(false, true)) {
            listener.onFailure(message)
            webSocket?.close(1001, "error")
            client.dispatcher.executorService.shutdown()
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sendSessionConfiguration(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            val detail = when (response?.code) {
                401 -> "API-Key wurde von OpenAI abgelehnt"
                429 -> "OpenAI-Limit erreicht. Bitte Guthaben und Rate-Limit prüfen."
                else -> throwable.localizedMessage ?: "WebSocket-Verbindung fehlgeschlagen"
            }
            fail(detail)
        }
    }

    private companion object {
        const val REALTIME_SESSION_MODEL = "gpt-realtime-2.1"
        const val TRANSCRIPTION_MODEL = "gpt-live-transcribe"
        const val MIN_AUDIO_BYTES = 4_800L // 100 ms of mono PCM16 at 24 kHz
        const val FINAL_TIMEOUT_MS = 20_000L
    }
}
