package com.yage.voiceflowkit.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lightweight connection / credential check against the AI Builders backend.
 *
 * Port of Swift `AIBuilderClient.testConnection`, reconciled with the proven
 * Android reference `AIBuildersAudioClient.testConnection`.
 *
 * DIVERGENCE FROM SWIFT (intentional): Swift issues `GET {base}/v1/usage/summary`
 * and requires a 2xx. The proven, confirmed-working Android reference instead
 * issues `POST {base}/v1/embeddings` with body `{"input":"ok"}` and accepts any
 * status `< 400`. Because the task mandates reusing the confirmed-working Android
 * mechanism, we follow the reference. The observable contract — "throws on a bad
 * endpoint/token, succeeds otherwise" — is the same.
 */
internal object AIBuilderConnectionClient {

    /**
     * Verify the endpoint + token are usable. Throws [RealtimeTranscriptionError]
     * on failure (the public facade re-wraps these into `VoiceFlowError`).
     */
    suspend fun testConnection(baseURL: String, token: String): Unit = withContext(Dispatchers.IO) {
        val cleanedToken = token.trim()
        if (cleanedToken.isEmpty()) {
            throw RealtimeTranscriptionError.MissingToken
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(RealtimeTranscriptionConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(RealtimeTranscriptionConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(RealtimeTranscriptionConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val url = RealtimeApiUrlBuilder.buildAPIURL(
            RealtimeApiUrlBuilder.normalizedBaseURL(baseURL),
            "/v1/embeddings",
        )
        val body = JSONObject().put("input", "ok").toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanedToken")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(RealtimeTranscriptionConfig.JSON_MEDIA_TYPE.toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code >= 400) {
                    throw RealtimeTranscriptionError.HttpError(response.code)
                }
            }
        } catch (error: IOException) {
            throw RealtimeTranscriptionError.ConnectionLost(error.message ?: "Connection test failed")
        }
    }
}
