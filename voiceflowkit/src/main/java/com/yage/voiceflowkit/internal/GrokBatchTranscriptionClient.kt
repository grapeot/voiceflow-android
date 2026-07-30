package com.yage.voiceflowkit.internal

import com.yage.voiceflowkit.TranscriptionResult
import com.yage.voiceflowkit.VoiceFlowError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Port of Swift `GrokBatchTranscribing`. */
internal interface GrokBatchTranscribing {
    suspend fun transcribe(
        audioFile: File,
        baseURL: String,
        token: String,
        terms: List<String>,
    ): TranscriptionResult
}

/**
 * Multipart upload to `POST /v1/audio/grok-transcription`.
 * Port of Swift `GrokBatchTranscriptionClient`.
 */
internal class GrokBatchTranscriptionClient(
    private val client: OkHttpClient = defaultClient(),
) : GrokBatchTranscribing {

    override suspend fun transcribe(
        audioFile: File,
        baseURL: String,
        token: String,
        terms: List<String>,
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() <= 0L || audioFile.length() > MAXIMUM_UPLOAD_BYTES) {
            throw VoiceFlowError.AudioConversionFailed
        }

        val url = try {
            RealtimeApiUrlBuilder.buildAPIURL(
                RealtimeApiUrlBuilder.normalizedBaseURL(baseURL),
                "/v1/audio/grok-transcription",
            )
        } catch (_: RealtimeTranscriptionError.InvalidBaseUrl) {
            throw VoiceFlowError.InvalidEndpoint
        } catch (_: Throwable) {
            throw VoiceFlowError.InvalidEndpoint
        }

        val cleanTerms = terms.map { it.trim() }.filter { it.isNotEmpty() }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio_file",
                audioFile.name,
                audioFile.asRequestBody(mimeType(audioFile.extension).toMediaType()),
            )
            .apply {
                if (cleanTerms.isNotEmpty()) {
                    addFormDataPart("terms", cleanTerms.joinToString(","))
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .post(multipart)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw VoiceFlowError.HttpError(response.code)
                }
                val body = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(body)
                } catch (_: Throwable) {
                    throw VoiceFlowError.Underlying("Invalid Grok transcription response")
                }
                val text = json.optString("text", "").trim()
                if (text.isEmpty()) throw VoiceFlowError.EmptyTranscript
                val requestId = json.optString("request_id", "").ifBlank {
                    java.util.UUID.randomUUID().toString()
                }
                TranscriptionResult(text = text, requestId = requestId)
            }
        } catch (error: VoiceFlowError) {
            throw error
        } catch (error: IOException) {
            throw VoiceFlowError.Underlying(error.message ?: "Grok transcription failed")
        }
    }

    companion object {
        const val MAXIMUM_UPLOAD_BYTES: Long = 32L * 1024L * 1024L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(RealtimeTranscriptionConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

        fun mimeType(extension: String): String =
            when (extension.lowercase()) {
                "m4a", "mp4" -> "audio/mp4"
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "aac" -> "audio/aac"
                "ogg", "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> "application/octet-stream"
            }
    }
}

/** Test double for route assertions. */
internal class MockGrokBatchTranscriptionClient(
    private val result: Result<TranscriptionResult>,
) : GrokBatchTranscribing {
    val calls = mutableListOf<Pair<File, List<String>>>()

    override suspend fun transcribe(
        audioFile: File,
        baseURL: String,
        token: String,
        terms: List<String>,
    ): TranscriptionResult {
        calls += audioFile to terms
        return result.getOrThrow()
    }
}
