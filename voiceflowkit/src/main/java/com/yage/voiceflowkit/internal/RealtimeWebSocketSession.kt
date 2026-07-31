package com.yage.voiceflowkit.internal

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single live WebSocket connection to the realtime transcription backend.
 *
 * Port of Swift `RealtimeTranscriptionSession` merged with the proven Android
 * reference `AIBuildersRealtimeSession` (opencode_android_client). The instance is
 * created *already ready* — i.e. the caller has already opened the OkHttp socket,
 * received the inbound `session_ready` frame, and wired this object's [onMessage]
 * as the listener's message handler. From there this class owns:
 *
 * - sending the `start` control frame, raw PCM16 binary chunks, `commit` and `stop`;
 * - parsing inbound frames into [RealtimeTranscriptEvent]s and pushing them to
 *   [onEvent];
 * - auto-sending `stop` once the strategy's required completion events arrive
 *   after a `commit`.
 *
 * The Swift code serialized all sends through a dedicated `RealtimeWebSocketSender`
 * actor. On Android OkHttp's `WebSocket.send` is already thread-safe and buffers
 * internally, but to preserve ordering guarantees we serialize sends through a
 * [Mutex] — the Kotlin analog of the Swift sender actor.
 */
internal class RealtimeWebSocketSession(
    private val webSocket: WebSocket,
    private val onEvent: (RealtimeTranscriptEvent) -> Unit,
    private val onEventsClosed: () -> Unit = {},
    private val requiresTurnCompletedBeforeStop: Boolean = false,
) {
    private val sendMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val committed = AtomicBoolean(false)
    private val shouldSendStopAfterCompleted = AtomicBoolean(false)
    private val transcriptCompleted = AtomicBoolean(false)
    private val turnCompleted = AtomicBoolean(false)
    private val stopSent = AtomicBoolean(false)
    private val stopSendFailed = AtomicBoolean(false)
    private val transportEnded = AtomicBoolean(false)

    @Volatile
    private var enqueuedAudioBytes = 0

    /** How many audio bytes have been enqueued for this socket so far. */
    val pendingCommitAudioBytes: Int
        get() = enqueuedAudioBytes

    /**
     * Handle one inbound text/binary frame. Wired into the OkHttp
     * [okhttp3.WebSocketListener] by the factory that builds this session.
     *
     * Tracks transcript/turn completion and, after commit, auto-sends `stop` once
     * the strategy's requirements are met. The frame is then translated via
     * [RealtimeMessageParser] and delivered as a [RealtimeTranscriptEvent].
     */
    fun onMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (type == "transcript_completed") transcriptCompleted.set(true)
            if (type == "turn_completed") turnCompleted.set(true)
            sendStopIfReady()
            if (type == "session_stopped" && !hasValidTerminalSequence()) {
                onEvent(
                    RealtimeTranscriptEvent.ErrorEvent(
                        if (stopSendFailed.get()) {
                            "Session stopped after the stop event failed to send"
                        } else {
                            "Session stopped before all terminal events and a successful stop"
                        },
                    ),
                )
                return
            }
            val event = RealtimeMessageParser.parseSocketEvent(json) ?: return
            onEvent(event)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to parse socket frame", error)
            onEvent(RealtimeTranscriptEvent.Disconnected)
        }
    }

    /** Called by the listener when the transport fails or the socket closes unexpectedly. */
    fun onTransportFailure() {
        if (closed.compareAndSet(false, true) && transportEnded.compareAndSet(false, true)) {
            onEvent(RealtimeTranscriptEvent.Disconnected)
            onEventsClosed()
        }
    }

    /** Send the `start` control frame. Must be sent right after `session_ready`. */
    suspend fun sendStartControl(model: String, vad: Boolean = false) {
        val message = RealtimeMessageParser.startControlMessage(
            model = model,
            vad = vad,
            silenceDurationMs = RealtimeTranscriptionConfig.SILENCE_DURATION_MS,
        )
        sendMutex.withLock {
            if (!webSocket.send(message)) {
                throw RealtimeTranscriptionError.WebsocketError("Failed to send start control")
            }
        }
    }

    /** Stream one raw PCM16 chunk as a BINARY frame. No-op once committed or closed. */
    suspend fun sendAudioChunk(chunk: ByteArray) {
        if (chunk.isEmpty() || committed.get() || closed.get()) return
        sendMutex.withLock {
            while (!closed.get() &&
                webSocket.queueSize() + chunk.size > MAX_QUEUED_BYTES
            ) {
                delay(QUEUE_BACKPRESSURE_POLL_MS)
            }
            if (closed.get()) {
                throw RealtimeTranscriptionError.ConnectionLost("WebSocket connection is closed")
            }
            if (!webSocket.send(chunk.toByteString())) {
                throw RealtimeTranscriptionError.WebsocketError("Failed to send audio chunk")
            }
            enqueuedAudioBytes += chunk.size
        }
    }

    /**
     * Send the `commit` control frame, ending the audio stream and asking the
     * server to transcribe. Guards the backend's minimum-buffer requirement
     * (100 ms = 4800 bytes); below that the backend rejects with "buffer too
     * small", so we fail fast with a recoverable [RealtimeTranscriptionError].
     */
    suspend fun sendCommit() {
        if (!committed.compareAndSet(false, true)) return
        if (enqueuedAudioBytes < RealtimeTranscriptionConfig.minCommitAudioBytes) {
            // Reset committed so a retry/recovery on a fresh socket can commit again.
            committed.set(false)
            throw RealtimeTranscriptionError.WebsocketError(
                "Insufficient audio buffer for commit ($enqueuedAudioBytes bytes)",
            )
        }
        shouldSendStopAfterCompleted.set(true)
        sendMutex.withLock {
            if (!webSocket.send(RealtimeTranscriptionConfig.COMMIT_MESSAGE)) {
                shouldSendStopAfterCompleted.set(false)
                throw RealtimeTranscriptionError.WebsocketError("Failed to send commit event")
            }
        }
        sendStopIfReady()
    }

    /** Send the `stop` control frame. No-op if already closed. */
    suspend fun sendStop() {
        if (closed.get()) return
        sendMutex.withLock {
            if (!webSocket.send(RealtimeTranscriptionConfig.STOP_MESSAGE)) {
                stopSendFailed.set(true)
                throw RealtimeTranscriptionError.WebsocketError("Failed to send stop event")
            }
            stopSent.set(true)
        }
    }

    /**
     * Heartbeat / liveness check. OkHttp already pings on its own interval
     * (configured via `pingInterval`), so an explicit ping here just verifies the
     * connection is not already torn down — matching opencode's `heartbeat()`
     * semantics, which throws if the socket is closed so the streamer can recover.
     */
    suspend fun ping() {
        if (closed.get()) {
            throw RealtimeTranscriptionError.ConnectionLost("WebSocket connection is closed")
        }
    }

    /** Tear down the socket and emit a `Disconnected` event (once). */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        webSocket.cancel()
        if (transportEnded.compareAndSet(false, true)) {
            onEvent(RealtimeTranscriptEvent.Disconnected)
        }
        onEventsClosed()
    }

    private fun sendStopIfReady() {
        if (!transcriptCompleted.get()) return
        if (requiresTurnCompletedBeforeStop && !turnCompleted.get()) return
        if (shouldSendStopAfterCompleted.compareAndSet(true, false)) {
            val sent = runCatching {
                webSocket.send(RealtimeTranscriptionConfig.STOP_MESSAGE)
            }.getOrDefault(false)
            if (sent) {
                stopSent.set(true)
            } else {
                stopSendFailed.set(true)
                onEvent(RealtimeTranscriptEvent.ErrorEvent("Failed to send stop event"))
            }
        }
    }

    private fun hasValidTerminalSequence(): Boolean =
        transcriptCompleted.get() &&
            (!requiresTurnCompletedBeforeStop || turnCompleted.get()) &&
            stopSent.get() &&
            !stopSendFailed.get()

    private companion object {
        private const val TAG = "VFRealtimeWSSession"
        internal const val MAX_QUEUED_BYTES = 1_048_576L
        private const val QUEUE_BACKPRESSURE_POLL_MS = 5L
    }
}
