package com.yage.voiceflowkit.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline stub transcriber. Port of Swift `MockRealtimeTranscriptionClient` +
 * `MockLiveSessionProxy`. Backs `VoiceFlowClient.makeStub` and the unit tests; it
 * performs no network I/O.
 *
 * A live session emits `Status(Connected)` immediately and, on `finalize`, emits the
 * configured [liveTranscript] as a completed delta followed by `Status(Idle)`,
 * invokes the partial callback, and returns the transcript. The bulk path returns
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

    /** Records exposed for tests to assert prompt/terms made it through the wiring. */
    suspend fun lastLiveContext(): RealtimeSessionContext = mutex.withLock { lastLiveContextValue }
    suspend fun lastBulkContext(): RealtimeSessionContext = mutex.withLock { lastBulkContextValue }

    override suspend fun beginLiveSession(
        baseURL: String,
        token: String,
        model: String,
        context: RealtimeSessionContext,
        onEvent: (RealtimeTranscriptEvent) -> Unit,
    ): RealtimeLiveTranscriptionSession {
        mutex.withLock { lastLiveContextValue = context }
        onEvent(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected))
        return MockLiveSessionProxy(liveTranscript, onEvent)
    }

    override suspend fun transcribeBulkPcm(
        pcm: ByteArray,
        baseURL: String,
        token: String,
        model: String,
        context: RealtimeSessionContext,
        onPartialTranscript: ((String) -> Unit)?,
    ): String {
        mutex.withLock { lastBulkContextValue = context }
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
    private val onEvent: (RealtimeTranscriptEvent) -> Unit,
) : RealtimeLiveTranscriptionSession {

    private val mutex = Mutex()
    private var phase: RealtimeConnectionPhase = RealtimeConnectionPhase.Connected
    private var appendedChunkCount = 0
    private var cancelled = false

    override suspend fun appendAudioChunk(chunk: ByteArray) {
        mutex.withLock { appendedChunkCount += 1 }
    }

    override suspend fun heartbeat() = Unit

    override suspend fun finalize(onPartialTranscript: ((String) -> Unit)?): String {
        mutex.withLock { phase = RealtimeConnectionPhase.Generating }
        onEvent(RealtimeTranscriptEvent.TextDelta(content = liveTranscript, isNewResponse = true))
        onEvent(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle))
        onPartialTranscript?.invoke(liveTranscript)
        mutex.withLock { phase = RealtimeConnectionPhase.Disconnected }
        return liveTranscript
    }

    override suspend fun cancel() {
        mutex.withLock {
            cancelled = true
            phase = RealtimeConnectionPhase.Disconnected
        }
    }

    override suspend fun connectionPhase(): RealtimeConnectionPhase = mutex.withLock { phase }
}
