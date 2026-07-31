package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.GrokBatchTranscribing
import com.yage.voiceflowkit.internal.GrokBatchTranscriptionClient
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
 * Supports three complete recording strategies:
 * - [VoiceFlowRecordingStrategy.OPENAI_REALTIME]: live WebSocket path
 * - [VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE]: GPT Live WebSocket path
 * - [VoiceFlowRecordingStrategy.GROK_BATCH]: file upload after Stop
 */
class VoiceFlowClient internal constructor(
    config: VoiceFlowConfig,
    private val transcriber: RealtimeTranscribing,
    private val grokTranscriber: GrokBatchTranscribing,
) {
    private val configMutex = Mutex()
    private var config: VoiceFlowConfig = config

    constructor(config: VoiceFlowConfig) : this(
        config = config,
        transcriber = RealtimeTranscriptionClient(),
        grokTranscriber = GrokBatchTranscriptionClient(),
    )

    suspend fun updateConfig(config: VoiceFlowConfig) {
        configMutex.withLock {
            this.config = config
        }
    }

    suspend fun currentConfig(): VoiceFlowConfig = configMutex.withLock { config }

    suspend fun startSession(): VoiceFlowSession =
        startSessionInternal(VoiceFlowRecordingStrategy.OPENAI_REALTIME)

    suspend fun startSession(strategy: VoiceFlowRecordingStrategy): VoiceFlowSession {
        if (!strategy.usesRealtimeTransport) throw VoiceFlowError.UnsupportedStrategy(strategy)
        return startSessionInternal(strategy)
    }

    private suspend fun startSessionInternal(strategy: VoiceFlowRecordingStrategy): VoiceFlowSession {
        val snapshot = configMutex.withLock { config }
        val token = currentToken(snapshot)
        val bridge = SessionEventBridge()
        val model = strategy.realtimeModel(snapshot.model)
        try {
            val live = transcriber.beginLiveSession(
                baseURL = snapshot.endpoint,
                token = token,
                model = model,
                strategy = strategy,
                context = RealtimeSessionContext(prompt = snapshot.prompt, terms = snapshot.terms),
                onEvent = { event -> bridge.emit(event) },
            )
            return VoiceFlowSession(
                underlying = live,
                eventBridge = bridge,
                strategy = strategy,
            )
        } catch (realtime: RealtimeTranscriptionError) {
            bridge.finish()
            throw VoiceFlowError.from(realtime)
        }
    }

    /**
     * One-shot transcription of an existing audio file using the OpenAI realtime
     * bulk path. Prefer [transcribe] with an explicit strategy when the file may
     * come from Grok Batch capture.
     */
    suspend fun transcribe(
        wavFile: File,
        onPartialTranscript: ((String) -> Unit)? = null,
    ): TranscriptionResult =
        transcribe(
            audioFile = wavFile,
            strategy = VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            onPartialTranscript = onPartialTranscript,
        )

    /**
     * Strategy-aware file transcription.
     *
     * - [VoiceFlowRecordingStrategy.OPENAI_REALTIME]: reads PCM from WAV and
     *   runs the bulk realtime pipeline (partials supported).
     * - [VoiceFlowRecordingStrategy.GROK_BATCH]: multipart upload to
     *   `/v1/audio/grok-transcription` (no partials; terms only, no prompt).
     */
    suspend fun transcribe(
        audioFile: File,
        strategy: VoiceFlowRecordingStrategy,
        onPartialTranscript: ((String) -> Unit)? = null,
    ): TranscriptionResult {
        val snapshot = configMutex.withLock { config }
        val token = currentToken(snapshot)
        return when (strategy) {
            VoiceFlowRecordingStrategy.GROK_BATCH -> {
                try {
                    grokTranscriber.transcribe(
                        audioFile = audioFile,
                        baseURL = snapshot.endpoint,
                        token = token,
                        terms = snapshot.terms,
                    )
                } catch (error: VoiceFlowError) {
                    throw error
                } catch (t: Throwable) {
                    throw VoiceFlowError.Underlying(t.toString())
                }
            }
            VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            -> {
                val pcm: ByteArray = try {
                    Pcm16WavWriter.readPcm(audioFile)
                } catch (_: Throwable) {
                    throw VoiceFlowError.AudioConversionFailed
                }
                try {
                    val text = transcriber.transcribeBulkPcm(
                        pcm = pcm,
                        baseURL = snapshot.endpoint,
                        token = token,
                        model = strategy.realtimeModel(snapshot.model),
                        strategy = strategy,
                        context = RealtimeSessionContext(prompt = snapshot.prompt, terms = snapshot.terms),
                        onPartialTranscript = onPartialTranscript,
                    )
                    TranscriptionResult(text = text, requestId = UUID.randomUUID().toString())
                } catch (realtime: RealtimeTranscriptionError) {
                    throw VoiceFlowError.from(realtime)
                }
            }
        }
    }

    suspend fun transcribe(
        preservedAudio: VoiceFlowPreservedAudio,
        onPartialTranscript: ((String) -> Unit)? = null,
    ): TranscriptionResult {
        val snapshot = configMutex.withLock { config }
        val token = currentToken(snapshot)
        val pcm = try {
            preservedAudio.file.readBytes()
        } catch (_: Throwable) {
            throw VoiceFlowError.AudioConversionFailed
        }
        if (pcm.isEmpty()) throw VoiceFlowError.EmptyTranscript
        val strategy = preservedAudio.strategy
        if (!strategy.usesRealtimeTransport) throw VoiceFlowError.UnsupportedStrategy(strategy)
        try {
            val text = transcriber.transcribeBulkPcm(
                pcm = pcm,
                baseURL = snapshot.endpoint,
                token = token,
                model = preservedAudio.model,
                strategy = strategy,
                context = RealtimeSessionContext(prompt = snapshot.prompt, terms = snapshot.terms),
                onPartialTranscript = onPartialTranscript,
            )
            return TranscriptionResult(text = text, requestId = preservedAudio.id)
        } catch (realtime: RealtimeTranscriptionError) {
            throw VoiceFlowError.from(realtime)
        }
    }

    fun discardPreservedAudio(preservedAudio: VoiceFlowPreservedAudio) {
        preservedAudio.file.delete()
    }

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
        } catch (realtime: RealtimeTranscriptionError) {
            throw VoiceFlowError.from(realtime)
        } catch (t: Throwable) {
            throw VoiceFlowError.Underlying(t.toString())
        }
    }

    private suspend fun currentToken(config: VoiceFlowConfig): String {
        val token = try {
            config.tokenProvider().trim()
        } catch (voiceFlow: VoiceFlowError) {
            throw voiceFlow
        } catch (_: Throwable) {
            throw VoiceFlowError.MissingToken
        }
        if (token.isEmpty()) throw VoiceFlowError.MissingToken
        return token
    }

    companion object {
        fun makeStub(
            config: VoiceFlowConfig = VoiceFlowConfig(tokenProvider = { "stub-token" }),
            liveTranscript: String = "Mock transcription",
            bulkTranscript: String? = null,
            grokTranscript: String = bulkTranscript ?: liveTranscript,
        ): VoiceFlowClient {
            val transcriber = MockRealtimeTranscriptionClient(
                liveTranscript = liveTranscript,
                bulkTranscript = bulkTranscript,
            )
            val grok = object : GrokBatchTranscribing {
                override suspend fun transcribe(
                    audioFile: File,
                    baseURL: String,
                    token: String,
                    terms: List<String>,
                ): TranscriptionResult =
                    TranscriptionResult(
                        text = grokTranscript,
                        requestId = UUID.randomUUID().toString(),
                    )
            }
            return VoiceFlowClient(
                config = config,
                transcriber = transcriber,
                grokTranscriber = grok,
            )
        }

        internal fun forTests(
            config: VoiceFlowConfig,
            transcriber: RealtimeTranscribing,
            grokTranscriber: GrokBatchTranscribing,
        ): VoiceFlowClient = VoiceFlowClient(config, transcriber, grokTranscriber)
    }
}
