package dev.s2tmic.companion.network

import dev.s2tmic.companion.data.TranscriptionProvider
import dev.s2tmic.companion.dictation.PcmAudio
import dev.s2tmic.companion.dictation.RecordedAudio
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Everything needed to upload one recording; kept after a failed upload so it can be sent again. */
class TranscriptionRequest(
    val provider: TranscriptionProvider,
    val model: String,
    val apiKey: String,
    /** ISO-639-1 code, or blank to let the model detect the language. */
    val language: String,
    val audio: RecordedAudio,
)

sealed interface TranscriptionResult {
    data class Success(val transcript: String) : TranscriptionResult

    /** [retryable] is true when sending the same recording again can succeed. */
    data class Failure(val message: String, val retryable: Boolean) : TranscriptionResult
}

/** Uploads recordings as WAV to the selected provider's OpenAI-compatible transcription API. */
object TranscriptionApi {
    /** Shared so connections stay open between dictations instead of paying a TLS handshake each time. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Opens the connection while the user is still speaking, so the upload can start right at stop. */
    fun warmUp(provider: TranscriptionProvider) {
        val request = Request.Builder().url(provider.endpoint).head().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    /** [onResult] runs on an OkHttp thread, and not at all once the returned call was cancelled. */
    fun transcribe(request: TranscriptionRequest, onResult: (TranscriptionResult) -> Unit): Call {
        val provider = request.provider
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "dictation.wav", WavBody(request.audio))
            .addFormDataPart("model", request.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (request.language.isNotBlank()) addFormDataPart("language", request.language) }
            .build()
        val httpRequest = Request.Builder()
            .url(provider.endpoint)
            .header("Authorization", "Bearer ${request.apiKey}")
            .apply { if (provider == TranscriptionProvider.OpenRouter) header("X-Title", "S2T Mic") }
            .post(body)
            .build()

        return client.newCall(httpRequest).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    onResult(TranscriptionResult.Failure(connectionError(provider, e), retryable = true))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { parse(provider, it.code, it.body?.string().orEmpty()) }
                    } catch (e: IOException) {
                        if (call.isCanceled()) return
                        TranscriptionResult.Failure(connectionError(provider, e), retryable = true)
                    }
                    onResult(result)
                }
            })
        }
    }

    private fun parse(provider: TranscriptionProvider, code: Int, body: String): TranscriptionResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (code !in 200..299) {
            val apiMessage = json?.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
            return TranscriptionResult.Failure(
                message = apiMessage ?: httpError(provider, code),
                retryable = code == 408 || code == 429 || code >= 500,
            )
        }
        if (json == null) {
            return TranscriptionResult.Failure("Unerwartete Antwort von ${provider.displayName}", retryable = true)
        }
        return TranscriptionResult.Success(json.optString("text").trim())
    }

    private fun httpError(provider: TranscriptionProvider, code: Int): String = when (code) {
        401 -> "${provider.displayName} API-Key wurde abgelehnt"
        402 -> "${provider.displayName}-Guthaben reicht nicht. Bitte Credits aufladen."
        413 -> "Die Aufnahme ist für ${provider.displayName} zu groß"
        429 -> "${provider.displayName}-Limit erreicht. Bitte Rate-Limit und Guthaben prüfen."
        else -> "${provider.displayName}-Fehler ($code)"
    }

    private fun connectionError(provider: TranscriptionProvider, error: IOException): String = when (error) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> "Keine Internetverbindung"
        is InterruptedIOException -> "${provider.displayName} antwortet nicht"
        is SSLException -> "Sichere Verbindung zu ${provider.displayName} fehlgeschlagen"
        else -> "Verbindung zu ${provider.displayName} abgebrochen"
    }

    /** Streams the WAV header and the recorded chunks without first joining them into one array. */
    private class WavBody(private val audio: RecordedAudio) : RequestBody() {
        override fun contentType(): MediaType = WAV_MEDIA_TYPE

        override fun contentLength(): Long = PcmAudio.WAV_HEADER_BYTES + audio.byteCount.toLong()

        override fun writeTo(sink: BufferedSink) {
            sink.write(PcmAudio.wavHeader(audio.byteCount))
            audio.chunks.forEach(sink::write)
        }
    }

    private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
}
