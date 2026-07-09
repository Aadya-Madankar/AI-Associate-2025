package com.example.memory

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.config.ApiKeyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Turns text into a dense vector for semantic recall. Returns null when unavailable. */
interface EmbeddingClient {
    /** Embed [text], or null on any failure (no key, offline, error). Callers must degrade. */
    suspend fun embed(text: String): FloatArray?
}

/**
 * Best-effort Gemini embeddings over OkHttp REST. Deliberately fail-soft: no key, no network, a
 * non-200, or a parse error all return `null`, and memory recall falls back to keyword + recency
 * (see [MemoryScoring]). So embeddings *enrich* recall when the key/network are present and never
 * block it when they are not.
 *
 * Uses the same on-device key list as the live session ([ApiKeyStore] first, then the build key).
 *
 * ponytail: model name hardcoded to text-embedding-004 (the widely-available embeddings model);
 * swap the constant if the project moves to a newer embeddings model.
 */
class GeminiEmbeddingClient(
    context: Context,
    private val model: String = "text-embedding-004",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Tight ceilings: embedding gates the (sequential) Live tool turn, and a blocking OkHttp call
    // won't observe the caller's coroutine timeout mid-flight — so callTimeout is the real bound.
    // On a slow/dead network we give up fast and recall degrades to keyword+recency.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
) : EmbeddingClient {

    private val appContext = context.applicationContext
    private val apiKeyStore = ApiKeyStore(appContext)

    override suspend fun embed(text: String): FloatArray? = withContext(ioDispatcher) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext null
        val key = firstKey() ?: return@withContext null
        runCatching {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$model:embedContent?key=$key"
            val payload = JSONObject()
                .put("model", "models/$model")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", clean))))
                .toString()
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val values = JSONObject(body)
                    .optJSONObject("embedding")
                    ?.optJSONArray("values")
                    ?: return@use null
                FloatArray(values.length()) { i -> values.optDouble(i, 0.0).toFloat() }
            }
        }.getOrElse {
            Log.d(TAG, "embed failed (falling back to keyword recall): ${it.message}")
            null
        }
    }

    private suspend fun firstKey(): String? {
        val inApp = runCatching { apiKeyStore.current() }.getOrDefault(emptyList())
        val build = BuildConfig.GEMINI_API_KEY
            .takeIf { it.isNotEmpty() && it != MISSING_KEY_SENTINEL }
        return (inApp.firstOrNull { it.isNotBlank() }) ?: build
    }

    private companion object {
        const val TAG = "EmbeddingClient"
        const val MISSING_KEY_SENTINEL = "MY_GEMINI_API_KEY"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
