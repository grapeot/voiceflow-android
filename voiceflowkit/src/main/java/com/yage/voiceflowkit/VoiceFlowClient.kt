package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.MockRealtimeTranscriptionClient
import com.yage.voiceflowkit.internal.Pcm16WavWriter
import com.yage.voiceflowkit.internal.RealtimeSessionContext
import com.yage.voiceflowkit.internal.RealtimeTranscribing
import com.yage.voiceflowkit.internal.RealtimeTranscriptionClient
import com.yage.voiceflowkit.internal.RealtimeTranscriptionError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Public entry point for VoiceFlowKit. Holds the config (endpoint, token
 * provider, optional prompt/terms) and creates sessions.
 *
 * Sessions are independent — you can start, stop, cancel, restart in
 * any order. The client itself is cheap; it's safe to construct one
 * per host-side controller or share a single instance.
 *
 * VoiceFlowKit V0 wraps the internal [RealtimeTranscribing] implementation.
 * Tests can inject a custom transcriber via the internal constructor.
 *
 * The Swift source models this as an `actor`. On Android we use a plain
 * class guarded by an internal [Mutex] so that config reads/writes are
 * serialized just like actor-isolated state.
 */
class VoiceFlowClient internal constructor(
    config: VoiceFlowConfig,
    private val transcriber: RealtimeTranscribing,
) {
    /** Serializes access to [config] so reads/writes don't race. */
    private val configMutex = Mutex()
    private var config: VoiceFlowConfig = config

    /**
     * Production constructor. Wires up the real WebSocket-backed
     * transcription pipeline.
     */
    constructor(config: VoiceFlowConfig) : this(config, RealtimeTranscriptionClient())

    /** Replace the entire config. Effective on the next call. */
    suspend fun updateConfig(config: VoiceFlowConfig) {
        configMutex.withLock {
            this.config = config
        }
    }

    /** Current config (read-only view for hosts that need to inspect). */
    suspend fun currentConfig(): VoiceFlowConfig = configMutex.withLock { config }

    /**
     * Start a realtime session. Host then pumps PCM chunks in,
     * optionally pings, and finalizes with [VoiceFlowSession.commitAndStop].
     */
    suspend fun startSession(): VoiceFlowSession {
        val snapshot = configMutex.withLock { config }
        val token = currentToken(snapshot)
        val bridge = SessionEventBridge()
        try {
            val live = transcriber.beginLiveSession(
                baseURL = snapshot.endpoint,
                token = token,
                model = snapshot.model,
                context = RealtimeSessionContext(prompt = snapshot.prompt, terms = snapshot.terms),
                onEvent = { event -> bridge.emit(event) },
            )
            return VoiceFlowSession(underlying = live, eventBridge = bridge)
        } catch (realtime: RealtimeTranscriptionError) {
            bridge.finish()
            throw VoiceFlowError.from(realtime)
        }
    }

    /**
     * One-shot transcription of an existing WAV file. Internally feeds the
     * PCM through the same realtime WS pipeline, gathers partial deltas,
     * and returns the final string.
     *
     * V0 only supports WAV input here — this matches what VoiceFlow's
     * resend path uses.
     */
    suspend fun transcribe(
        wavFile: File,
        onPartialTranscript: ((String) -> Unit)? = null,
    ): TranscriptionResult {
        val snapshot = configMutex.withLock { config }
        val token = currentToken(snapshot)
        val pcm: ByteArray = try {
            Pcm16WavWriter.readPcm(wavFile)
        } catch (t: Throwable) {
            throw VoiceFlowError.AudioConversionFailed
        }
        try {
            val text = transcriber.transcribeBulkPcm(
                pcm = pcm,
                baseURL = snapshot.endpoint,
                token = token,
                model = snapshot.model,
                context = RealtimeSessionContext(prompt = snapshot.prompt, terms = snapshot.terms),
                onPartialTranscript = onPartialTranscript,
            )
            return TranscriptionResult(text = text, requestId = UUID.randomUUID().toString())
        } catch (realtime: RealtimeTranscriptionError) {
            throw VoiceFlowError.from(realtime)
        }
    }

    /**
     * Verify endpoint reachability + token validity. Throws on any failure.
     * Non-[VoiceFlowError] causes are wrapped in [VoiceFlowError.Underlying],
     * mirroring the Swift facade.
     */
    suspend fun testConnection() {
        val snapshot = configMutex.withLock { config }
        val token = currentToken(snapshot)
        try {
            com.yage.voiceflowkit.internal.AIBuilderConnectionClient.testConnection(
                baseURL = snapshot.endpoint,
                token = token,
            )
        } catch (voiceFlow: VoiceFlowError) {
            throw voiceFlow
        } catch (t: Throwable) {
            throw VoiceFlowError.Underlying(t.toString())
        }
    }

    /**
     * Fetch + trim the bearer token. Empty token => [VoiceFlowError.MissingToken].
     * Any non-VoiceFlowError thrown by the provider is also coerced to
     * MissingToken, matching the Swift `currentToken()` behavior.
     */
    private suspend fun currentToken(config: VoiceFlowConfig): String {
        val token = try {
            config.tokenProvider().trim()
        } catch (voiceFlow: VoiceFlowError) {
            throw voiceFlow
        } catch (t: Throwable) {
            throw VoiceFlowError.MissingToken
        }
        if (token.isEmpty()) throw VoiceFlowError.MissingToken
        return token
    }

    companion object {
        /**
         * Offline stub client. Does not open a WebSocket; [startSession]
         * returns a session whose [VoiceFlowSession.commitAndStop] resolves
         * to the canned [liveTranscript] after emitting a connected → idle
         * event sequence. [transcribe] returns [bulkTranscript] (falls back
         * to [liveTranscript] if unset).
         *
         * Use this in host UI-test launch modes and design-time scaffolding.
         * Tokens in `config.tokenProvider` are ignored; the stub does not
         * authenticate. The returned client is otherwise indistinguishable
         * from a production one.
         */
        fun makeStub(
            config: VoiceFlowConfig = VoiceFlowConfig(tokenProvider = { "stub-token" }),
            liveTranscript: String = "Mock transcription",
            bulkTranscript: String? = null,
        ): VoiceFlowClient {
            val transcriber = MockRealtimeTranscriptionClient(
                liveTranscript = liveTranscript,
                bulkTranscript = bulkTranscript,
            )
            return VoiceFlowClient(config = config, transcriber = transcriber)
        }
    }
}
