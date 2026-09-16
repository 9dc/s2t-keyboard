package dev.s2tmic.companion.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SttModel(val id: String, val name: String)

/** Fetches the public list of speech-to-text models offered by OpenRouter. */
object SttModelsClient {
    private const val MODELS_URL =
        "https://openrouter.ai/api/v1/models?output_modalities=transcription"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchOpenRouterModels(): List<SttModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(MODELS_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Modell-Liste konnte nicht geladen werden (${response.code})")
            }
            val payload = response.body?.string().orEmpty()
            val data = JSONObject(payload).optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: id
                    add(SttModel(id, name))
                }
            }.sortedBy { it.name.lowercase() }
        }
    }
}
