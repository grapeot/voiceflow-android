package com.yage.voiceflowkit.internal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Abstraction over the realtime transcription backend. Port of the Swift
 * `RealtimeTranscribing` protocol. The production implementation is
 * [RealtimeTranscriptionClient]; tests / `makeStub` use
 * [MockRealtimeTranscriptionClient].
 */
internal interface RealtimeTranscribing {
    suspend fun beginLiveSession(
        baseURL: String,
        token: String,
        model: String,
        context: RealtimeSessionContext,
        onEvent: (RealtimeTranscriptEvent) -> Unit,
    ): RealtimeLiveTranscriptionSession

    suspend fun transcribeBulkPcm(
        pcm: ByteArray,
        baseURL: String,
        token: String,
        model: String,
        context: RealtimeSessionContext,
        onPartialTranscript: ((String) -> Unit)?,
    ): String
}

/**
 * A live, recoverable transcription session. Port of the Swift
 * `RealtimeLiveTranscriptionSession` protocol. The production implementation is
 * [RealtimeLiveSessionHandle].
 */
internal interface RealtimeLiveTranscriptionSession {
    suspend fun appendAudioChunk(chunk: ByteArray)
    suspend fun heartbeat()
    suspend fun finalize(onPartialTranscript: ((String) -> Unit)?): String
    suspend fun cancel()
    suspend fun connectionPhase(): RealtimeConnectionPhase
}

/**
 * Production [RealtimeTranscribing]. Port of Swift `RealtimeTranscriptionClient`,
 * built on the proven Android flow from `AIBuildersAudioClient`
 * (createRealtimeSession + openRealtimeWebSocketSession).
 *
 * Owns one [OkHttpClient] configured with the same timeouts and ping interval as
 * the reference. The [context] is only used to locate a cache directory for the
 * disk-backed [AudioChunkCache]; when null we fall back to the system temp dir so
 * the client stays unit-testable.
 */
internal class RealtimeTranscriptionClient(
    private val context: Context? = null,
) : RealtimeTranscribing {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(RealtimeTranscriptionConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(RealtimeTranscriptionConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(RealtimeTranscriptionConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(RealtimeTranscriptionConfig.HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cacheDirectory: File
        get() = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")

    override suspend fun beginLiveSession(
        baseURL: String,
        token: String,
        model: String,
        context: RealtimeSessionContext,
        onEvent: (RealtimeTranscriptEvent) -> Unit,
    ): RealtimeLiveTranscriptionSession {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            throw RealtimeTranscriptionError.MissingToken
        }

        val cache = AudioChunkCache(cacheDirectory)

        // Forward declaration so the makeSession closure (used both for recovery and
        // the initial connect) can route inbound socket events through the handle's
        // bookkeeping + UI filter, mirroring Swift's `deliverLiveSessionEvent`.
        lateinit var handle: RealtimeLiveSessionHandle

        val makeSession: suspend () -> RealtimeWebSocketSession = {
            makeSession(
                baseURL = baseURL,
                token = trimmedToken,
                model = model,
                vad = false,
                context = context,
            ) { event ->
                scope.launch {
                    handle.ingestServerEvent(event)
                    if (handle.shouldNotifyUI(event)) {
                        onEvent(event)
                    }
                }
            }
        }

        handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = onEvent,
            makeSession = makeSession,
        )

        // Connect the first socket off the caller's thread, like Swift's detached Task.
        scope.launch {
            try {
                val initialSession = makeSession()
                handle.attachInitialSession(initialSession)
            } catch (error: Exception) {
                onEvent(RealtimeTranscriptEvent.RecoveryFailed(error.toString()))
            }
        }

        return handle
    }

    override suspend fun transcribeBulkPcm(
        pcm: ByteArray,
        baseURL: String,
        token: String,
        model: String,
        context: RealtimeSessionContext,
        onPartialTranscript: ((String) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) {
            throw RealtimeTranscriptionError.EmptyTranscript
        }

        val progress = BulkTranscriptionProgress()
        val session = makeSession(
            baseURL = baseURL,
            token = token,
            model = model,
            vad = false,
            context = context,
        ) { event ->
            scope.launch { progress.handle(event, onPartialTranscript) }
        }

        try {
            var start = 0
            while (start < pcm.size) {
                val end = minOf(start + RealtimeTranscriptionConfig.REPLAY_CHUNK_SIZE, pcm.size)
                session.sendAudioChunk(pcm.copyOfRange(start, end))
                start = end
            }
            session.sendCommit()

            val deadline = System.currentTimeMillis() + RealtimeTranscriptionConfig.FINALIZE_TIMEOUT_MS
            while (!progress.isFinished() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100)
            }

            progress.receivedError()?.let { message ->
                throw RealtimeTranscriptionError.WebsocketError(message)
            }
            val trimmed = progress.transcript().trim()
            if (trimmed.isEmpty()) {
                throw RealtimeTranscriptionError.EmptyTranscript
            }
            trimmed
        } finally {
            session.close()
        }
    }

    /**
     * Create one ready-to-stream [RealtimeWebSocketSession]: POST the session-create
     * request, open the WebSocket, wait for `session_ready`, send the `start` control
     * frame, then hand back the wrapper. Port of Swift `makeSession`, using the proven
     * Android handshake from `AIBuildersAudioClient.openRealtimeWebSocketSession`.
     */
    private suspend fun makeSession(
        baseURL: String,
        token: String,
        model: String,
        vad: Boolean,
        context: RealtimeSessionContext,
        onEvent: (RealtimeTranscriptEvent) -> Unit,
    ): RealtimeWebSocketSession = withContext(Dispatchers.IO) {
        val normalizedBase = RealtimeApiUrlBuilder.normalizedBaseURL(baseURL)
        val createResponse = createRealtimeSession(normalizedBase, token, model, vad, context)
        val websocketURL = RealtimeApiUrlBuilder.realtimeWebSocketURL(
            normalizedBase,
            createResponse.wsUrl,
        )

        val readySignal = CompletableDeferred<Unit>()
        // The session wrapper is assigned before any non-ready frame can reach it.
        var liveSession: RealtimeWebSocketSession? = null

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = runCatching { JSONObject(text).optString("type") }.getOrDefault("")
                if (type == "session_ready") {
                    onEvent(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected))
                    if (!readySignal.isCompleted) readySignal.complete(Unit)
                    return
                }
                liveSession?.onMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!readySignal.isCompleted) readySignal.completeExceptionally(t)
                liveSession?.onTransportFailure()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                liveSession?.onTransportFailure()
            }
        }

        val request = Request.Builder().url(websocketURL).build()
        val webSocket = httpClient.newWebSocket(request, listener)
        val session = RealtimeWebSocketSession(webSocket = webSocket, onEvent = onEvent)
        liveSession = session

        try {
            readySignal.await()
        } catch (error: Exception) {
            webSocket.cancel()
            throw RealtimeTranscriptionError.WebsocketError(
                "WebSocket failed before session_ready: ${error.message}",
            )
        }

        session.sendStartControl(model = model, vad = vad)
        session
    }

    private fun createRealtimeSession(
        baseURL: String,
        token: String,
        model: String,
        vad: Boolean,
        context: RealtimeSessionContext,
    ): RealtimeSessionCreateResponse {
        val url = RealtimeApiUrlBuilder.buildAPIURL(
            baseURL,
            RealtimeTranscriptionConfig.SESSION_CREATE_PATH,
        )
        val payload = JSONObject()
            .put("model", model)
            .put("vad", vad)
            .put("silence_duration_ms", RealtimeTranscriptionConfig.SILENCE_DURATION_MS)

        context.prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("prompt", it) }
        if (context.terms.isNotEmpty()) {
            val jsonTerms = JSONArray()
            context.terms.forEach { jsonTerms.put(it) }
            payload.put("terms", jsonTerms)
        }

        Log.d(
            TAG,
            "session.create model=$model hasPrompt=${context.prompt?.isNotBlank() == true} " +
                "termsCount=${context.terms.size}",
        )

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(RealtimeTranscriptionConfig.JSON_MEDIA_TYPE.toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code >= 400) {
                throw RealtimeTranscriptionError.HttpError(response.code)
            }
            val bodyText = response.body?.string()
                ?: throw IOException("Create session returned empty body")
            val bodyJson = JSONObject(bodyText)
            return RealtimeSessionCreateResponse(
                sessionId = bodyJson.getString("session_id"),
                wsUrl = bodyJson.getString("ws_url"),
            )
        }
    }

    private companion object {
        private const val TAG = "VFRealtimeClient"
    }
}

/**
 * Decoded body of the session-create POST. Mirrors Swift
 * `RealtimeSessionCreateResponse` (`session_id` / `ws_url`).
 */
internal data class RealtimeSessionCreateResponse(
    val sessionId: String,
    val wsUrl: String,
)
