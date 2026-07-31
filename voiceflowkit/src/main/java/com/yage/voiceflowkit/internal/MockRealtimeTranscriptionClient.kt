package com.yage.voiceflowkit.internal

import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Offline stub transcriber. Port of Swift `MockRealtimeTranscriptionClient` +
 * `MockLiveSessionProxy`. Backs `VoiceFlowClient.makeStub` and the unit tests; it
 * performs no network I/O.
 *
 * A live session emits `Status(Connected)` immediately and, on `finalize`, emits the
 * configured [liveTranscript] through the accumulated callback followed by
 * `Status(Idle)`, and returns the transcript. The bulk path returns
 * [bulkTranscript] (falling back to [liveTranscript]) and invokes the partial
 * callback twice, matching the Swift mock so tests can assert the wiring.
 */
internal class MockRealtimeTranscriptionClient(
    private val liveTranscript: String,
    private val bulkTranscript: String?,
) : RealtimeTranscribing {

    private val mutex = Mutex()
    private var lastLiveContextValue: RealtimeSessionContext = RealtimeSessionContext()
    private var lastBulkContextValue: RealtimeSessionContext = RealtimeSessionContext()
    private var lastLiveModelValue: String? = null
    private var lastBulkModelValue: String? = null
    private var lastLiveStrategyValue: VoiceFlowRecordingStrategy? = null
    private var lastBulkStrategyValue: VoiceFlowRecordingStrategy? = null

    /** Records exposed for tests to assert prompt/terms made it through the wiring. */
    suspend fun lastLiveContext(): RealtimeSessionContext = mutex.withLock { lastLiveContextValue }
    suspend fun lastBulkContext(): RealtimeSessionContext = mutex.withLock { lastBulkContextValue }
    suspend fun lastLiveModel(): String? = mutex.withLock { lastLiveModelValue }
    suspend fun lastBulkModel(): String? = mutex.withLock { lastBulkModelValue }
    suspend fun lastLiveStrategy(): VoiceFlowRecordingStrategy? = mutex.withLock { lastLiveStrategyValue }
    suspend fun lastBulkStrategy(): VoiceFlowRecordingStrategy? = mutex.withLock { lastBulkStrategyValue }

    override suspend fun beginLiveSession(
        baseURL: String,
        token: String,
        model: String,
        strategy: VoiceFlowRecordingStrategy,
        context: RealtimeSessionContext,
        onEvent: (RealtimeTranscriptEvent) -> Unit,
    ): RealtimeLiveTranscriptionSession {
        mutex.withLock {
            lastLiveContextValue = context
            lastLiveModelValue = model
            lastLiveStrategyValue = strategy
        }
        onEvent(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected))
        return MockLiveSessionProxy(liveTranscript, strategy, model, onEvent)
    }

    override suspend fun transcribeBulkPcm(
        pcm: ByteArray,
        baseURL: String,
        token: String,
        model: String,
        strategy: VoiceFlowRecordingStrategy,
        context: RealtimeSessionContext,
        onPartialTranscript: ((String) -> Unit)?,
    ): String {
        mutex.withLock {
            lastBulkContextValue = context
            lastBulkModelValue = model
            lastBulkStrategyValue = strategy
        }
        val text = bulkTranscript ?: liveTranscript
        onPartialTranscript?.invoke(text)
        onPartialTranscript?.invoke(text)
        return text
    }
}

/**
 * Mock live session. Port of Swift `MockLiveSessionProxy`. Tracks a phase that walks
 * Connected -> Generating -> Disconnected across `finalize`, records appended chunks,
 * and replays a deterministic finalize event sequence.
 */
private class MockLiveSessionProxy(
    private val liveTranscript: String,
    private val strategy: VoiceFlowRecordingStrategy,
    private val model: String,
    private val onEvent: (RealtimeTranscriptEvent) -> Unit,
) : RealtimeLiveTranscriptionSession {

    private val mutex = Mutex()
    private var phase: RealtimeConnectionPhase = RealtimeConnectionPhase.Connected
    private var appendedChunkCount = 0
    private var appendedPcm = ByteArray(0)
    private var cancelled = false

    override suspend fun appendAudioChunk(chunk: ByteArray) {
        mutex.withLock {
            appendedChunkCount += 1
            appendedPcm += chunk
        }
    }

    override suspend fun heartbeat() = Unit

    override suspend fun finalize(onPartialTranscript: ((String) -> Unit)?): String {
        mutex.withLock { phase = RealtimeConnectionPhase.Generating }
        onPartialTranscript?.invoke(liveTranscript)
        onEvent(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle))
        mutex.withLock { phase = RealtimeConnectionPhase.Disconnected }
        return liveTranscript
    }

    override suspend fun cancel() {
        mutex.withLock {
            cancelled = true
            phase = RealtimeConnectionPhase.Disconnected
        }
    }

    override suspend fun abortPreservingAudio(): VoiceFlowPreservedAudio? {
        val pcm = mutex.withLock {
            cancelled = true
            phase = RealtimeConnectionPhase.Disconnected
            appendedPcm
        }
        if (pcm.isEmpty()) return null
        val file = File.createTempFile("voiceflow-stub-preserved", ".pcm")
        file.writeBytes(pcm)
        return VoiceFlowPreservedAudio(
            byteCount = pcm.size,
            strategy = strategy,
            model = model,
            file = file,
        )
    }

    override suspend fun connectionPhase(): RealtimeConnectionPhase = mutex.withLock { phase }
}
