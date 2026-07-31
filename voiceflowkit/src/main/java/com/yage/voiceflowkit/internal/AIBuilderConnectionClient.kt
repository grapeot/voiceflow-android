package com.yage.voiceflowkit.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lightweight connection / credential check against the AI Builders backend.
 *
 * Aligned with Swift `AIBuilderClient.testConnection`:
 * `GET {base}/v1/usage/summary` with Bearer token; requires HTTP 2xx.
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
            "/v1/usage/summary",
        )

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $cleanedToken")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw RealtimeTranscriptionError.HttpError(response.code)
                }
            }
        } catch (error: IOException) {
            throw RealtimeTranscriptionError.ConnectionLost(error.message ?: "Connection test failed")
        }
    }
}
