package com.yage.voiceflow.service

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Faithful port of the iOS `Services/OpenCodeClient.swift` using OkHttp.
 *
 * Flow parity with iOS:
 *  - [testConnection]: GET `{base}/session` with Basic auth, expect HTTP 200.
 *  - [sendTranscript]: POST `{base}/session` (Basic auth, body `{}`) -> decode
 *    `{id}` (expect 200), then POST `{base}/session/{id}/prompt_async` with the
 *    parts/model/agent body (expect 204).
 *
 * URL validation matches iOS `validatedBaseURL`: scheme + host required; no
 * user/password/query/fragment; trailing-slash path stripped; HTTPS always
 * allowed; HTTP allowed ONLY for loopback (localhost / 127.0.0.1 / ::1) and
 * Tailscale (`*.ts.net`) hosts.
 */
class OpenCodeClient(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    suspend fun testConnection(serverURL: String, username: String, password: String) =
        withContext(Dispatchers.IO) {
            val base = validatedBaseURL(serverURL)
            val request = Request.Builder()
                .url("$base/session")
                .get()
                .header("Authorization", authHeaderValue(username, password))
                .build()

            execute(request, timeoutSeconds = 10).use { response ->
                if (response.code != 200) {
                    throw OpenCodeClientError.SessionCreationFailed
                }
            }
        }

    suspend fun sendTranscript(text: String, serverURL: String, username: String, password: String) =
        withContext(Dispatchers.IO) {
            val base = validatedBaseURL(serverURL)
            val sessionId = createSession(base, username, password)
            sendPrompt(base, sessionId, text, username, password)
        }

    private suspend fun createSession(base: String, username: String, password: String): String {
        val body = JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$base/session")
            .post(body)
            .header("Content-Type", "application/json")
            .header("Authorization", authHeaderValue(username, password))
            .build()

        execute(request, timeoutSeconds = 10).use { response ->
            if (response.code != 200) {
                throw OpenCodeClientError.SessionCreationFailed
            }
            val raw = response.body?.string().orEmpty()
            val id = try {
                JSONObject(raw).optString("id")
            } catch (_: Throwable) {
                throw OpenCodeClientError.InvalidResponse
            }
            if (id.isEmpty()) {
                throw OpenCodeClientError.InvalidResponse
            }
            return id
        }
    }

    private suspend fun sendPrompt(
        base: String,
        sessionId: String,
        text: String,
        username: String,
        password: String,
    ) {
        val payload = JSONObject().apply {
            put(
                "parts",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
            put(
                "model",
                JSONObject().apply {
                    put("modelID", MODEL_ID)
                    put("providerID", PROVIDER_ID)
                },
            )
            put("agent", AGENT)
        }

        val request = Request.Builder()
            .url("$base/session/${Uri.encode(sessionId)}/prompt_async")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeaderValue(username, password))
            .build()

        execute(request, timeoutSeconds = 60).use { response ->
            if (response.code != 204) {
                throw OpenCodeClientError.PromptSendFailed
            }
        }
    }

    internal fun authHeaderValue(username: String, password: String): String {
        val credentials = "$username:$password"
        val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /**
     * Validate + normalize the base URL. Throws [OpenCodeClientError.InvalidURL]
     * or [OpenCodeClientError.InsecureRemoteURL]. Returns the normalized base
     * (scheme://host[:port], trailing slash stripped) as a string.
     */
    internal fun validatedBaseURL(serverURL: String): String {
        val uri = try {
            Uri.parse(serverURL.trim())
        } catch (_: Throwable) {
            throw OpenCodeClientError.InvalidURL
        }

        val scheme = uri.scheme?.lowercase() ?: throw OpenCodeClientError.InvalidURL
        val host = uri.host?.lowercase() ?: throw OpenCodeClientError.InvalidURL

        // No user/password (userInfo), query, or fragment allowed.
        if (!uri.userInfo.isNullOrEmpty()) throw OpenCodeClientError.InvalidURL
        if (!uri.query.isNullOrEmpty()) throw OpenCodeClientError.InvalidURL
        if (!uri.fragment.isNullOrEmpty()) throw OpenCodeClientError.InvalidURL

        if (scheme == "http" && !allowsInsecureHttp(host)) {
            throw OpenCodeClientError.InsecureRemoteURL
        }
        if (scheme != "https" && scheme != "http") {
            throw OpenCodeClientError.InvalidURL
        }

        // Strip a path that is only a trailing slash (mirrors iOS path == "/" -> "").
        val path = uri.path.orEmpty()
        val normalizedPath = if (path == "/") "" else path.trimEnd('/')

        val portSegment = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$portSegment$normalizedPath"
    }

    private suspend fun execute(request: Request, timeoutSeconds: Long): Response {
        val client = httpClient.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return withContext(Dispatchers.IO) {
            suspendCoroutine { cont ->
                val call = client.newCall(request)
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(OpenCodeClientError.SessionCreationFailed)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response)
                    }
                })
            }
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://localhost:4096"
        const val DEFAULT_USERNAME = "opencode"
        const val MODEL_ID = "gpt-5.5"
        const val PROVIDER_ID = "openai"
        const val AGENT = "Sisyphus - Ultraworker"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** iOS `allowsInsecureHTTP`: loopback or Tailscale host. */
        fun allowsInsecureHttp(host: String): Boolean = isLoopbackHost(host) || isTailscaleHost(host)

        private fun isLoopbackHost(host: String): Boolean =
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

        private fun isTailscaleHost(host: String): Boolean = host.endsWith(".ts.net")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}

/**
 * Port of the iOS `OpenCodeClientError`. Each case maps to a localized string
 * key the caller can surface; [messageKey] is consumed by the Settings/Record
 * status lines.
 */
sealed class OpenCodeClientError(val messageKey: String) : Exception() {
    data object InvalidURL : OpenCodeClientError("opencode.error.invalidURL")
    data object InsecureRemoteURL : OpenCodeClientError("opencode.error.insecureRemoteURL")
    data object InvalidResponse : OpenCodeClientError("opencode.error.invalidResponse")
    data object SessionCreationFailed : OpenCodeClientError("opencode.error.sessionCreationFailed")
    data object PromptSendFailed : OpenCodeClientError("opencode.error.promptSendFailed")
}
