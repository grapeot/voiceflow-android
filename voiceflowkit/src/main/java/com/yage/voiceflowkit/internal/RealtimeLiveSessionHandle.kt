package com.yage.voiceflowkit.internal

import android.util.Log
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * The recovery / finalize orchestrator for a live transcription session.
 *
 * Port of Swift `RealtimeLiveSessionHandle` (Internal/RealtimeTranscriptionClient.swift),
 * using the proven concurrency idiom from the Android reference
 * `RealtimeSpeechStreamer` (a [Mutex] guarding `session` / `isRecovering` instead of
 * a Swift actor).
 *
 * Responsibilities:
 * - Every captured chunk is appended to [cache] first, then sent on the current
 *   socket. A send failure triggers [recover].
 * - [recover] opens a brand-new socket (up to [RealtimeTranscriptionConfig.MAX_RECOVER_ATTEMPTS]
 *   tries with exponential backoff) and replays the full cache so the server sees
 *   the complete audio stream again.
 * - [finalize] commits the audio and waits for the server to deliver the final
 *   transcript, with a strategy-aware timeout. GPT Realtime may retry once on a fresh
 *   socket; GPT Live leaves retries to an explicit host action.
 *
 * Server events arrive through generation-gated [ingestServerEvent]. Raw deltas and
 * recoverable "buffer too small" noise stay out of the public event stream.
 */
internal class RealtimeLiveSessionHandle(
    private val cache: AudioChunkCache,
    private val onEvent: (RealtimeTranscriptEvent) -> Unit,
    private val makeSession: suspend (Long) -> RealtimeWebSocketSession,
    private val strategy: VoiceFlowRecordingStrategy,
    private val model: String,
) : RealtimeLiveTranscriptionSession {

    private val mutex = Mutex()
    private val audioMutex = Mutex()
    private var session: RealtimeWebSocketSession? = null
    private var generationCounter = INITIAL_GENERATION
    private var ownedGeneration: Long? = INITIAL_GENERATION
    private val initialConnection = CompletableDeferred<Unit>()
    private var isRecovering = false
    private var phase: RealtimeConnectionPhase = RealtimeConnectionPhase.Connecting

    // Finalize state. Guarded by [mutex] for reads/writes that race with server events.
    private var isFinalizing = false
    private var finalizeSignal: CompletableDeferred<Unit>? = null
    private var finalizeText = FinalizeTranscriptAccumulator()
    private var finalizePartialCallback: ((String) -> Unit)? = null
    private var audioDisposition = AudioDisposition.Active
    private var preservedAudio: VoiceFlowPreservedAudio? = null
    private var isTerminated = false

    override suspend fun connectionPhase(): RealtimeConnectionPhase = mutex.withLock { phase }

    /**
     * Initial connect path. Replays whatever is already cached into [newSession]
     * (normally nothing on a fresh start) and adopts it as the live session.
     * Mirrors Swift `attachInitialSession`.
     */
    suspend fun attachInitialSession(
        newSession: RealtimeWebSocketSession,
        generation: Long = INITIAL_GENERATION,
    ) {
        val shouldAttach = mutex.withLock {
            if (isTerminated || session != null || isRecovering || ownedGeneration != generation) {
                false
            } else {
                isRecovering = true
                phase = RealtimeConnectionPhase.Recovering
                true
            }
        }
        if (!shouldAttach) {
            newSession.close()
            initialConnection.completeExceptionally(
                RealtimeTranscriptionError.SessionUnavailable,
            )
            return
        }
        var attached = false
        try {
            audioMutex.withLock {
                replayCache(newSession)
                mutex.withLock {
                    if (!isTerminated && ownedGeneration == generation) {
                        session = newSession
                        phase = if (isFinalizing) {
                            RealtimeConnectionPhase.Generating
                        } else {
                            RealtimeConnectionPhase.Connected
                        }
                        attached = true
                    }
                }
            }
            if (attached) {
                initialConnection.complete(Unit)
            } else {
                newSession.close()
                initialConnection.completeExceptionally(
                    RealtimeTranscriptionError.SessionUnavailable,
                )
            }
        } catch (error: CancellationException) {
            initialConnection.completeExceptionally(error)
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Initial attach replay failed", error)
            newSession.close()
            mutex.withLock {
                if (ownedGeneration == generation) ownedGeneration = null
            }
            initialConnection.completeExceptionally(error)
            onEvent(RealtimeTranscriptEvent.RecoveryFailed(error.toString()))
        } finally {
            mutex.withLock { isRecovering = false }
        }
    }

    suspend fun failInitialConnection(generation: Long, error: Throwable): Boolean {
        val accepted = mutex.withLock {
            if (ownedGeneration != generation || initialConnection.isCompleted) {
                false
            } else {
                ownedGeneration = null
                phase = RealtimeConnectionPhase.Disconnected
                true
            }
        }
        if (accepted) initialConnection.completeExceptionally(error)
        return accepted
    }

    override suspend fun appendAudioChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        var recoveryReason: Throwable? = null
        var failedGeneration: Long? = null
        audioMutex.withLock {
            if (mutex.withLock {
                    audioDisposition != AudioDisposition.Active || isTerminated || isFinalizing
                }
            ) {
                return
            }
            cache.append(chunk)
            val active = mutex.withLock {
                if (isRecovering) null else session?.let { it to ownedGeneration }
            } ?: return
            try {
                active.first.sendAudioChunk(chunk)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recoveryReason = error
                failedGeneration = active.second
            }
        }
        recoveryReason?.let { recover(it, expectedGeneration = failedGeneration) }
    }

    override suspend fun heartbeat() {
        if (mutex.withLock { isTerminated }) return
        val active = mutex.withLock {
            if (isRecovering) null else session?.let { it to ownedGeneration }
        } ?: return
        try {
            active.first.ping()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recover(error, expectedGeneration = active.second)
        }
    }

    /**
     * Commit the buffered audio and wait for the server to produce the final
     * transcript. GPT Realtime gets one recovery retry; GPT Live gets exactly one
     * attempt because replaying a consumed paced turn requires an explicit host action.
     */
    override suspend fun finalize(onPartialTranscript: ((String) -> Unit)?): String {
        audioMutex.withLock {
            mutex.withLock {
                isFinalizing = true
                finalizeText.reset()
                finalizePartialCallback = onPartialTranscript
                phase = RealtimeConnectionPhase.Generating
            }
        }

        var lastError: Throwable = RealtimeTranscriptionError.EmptyTranscript
        try {
            val maxAttempts = if (strategy == VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) 1 else 2
            for (attempt in 0 until maxAttempts) {
                mutex.withLock { finalizeText.reset() }
                ensureSessionReadyForFinalize()
                var active = mutex.withLock {
                    session?.let { it to ownedGeneration }
                }
                    ?: throw RealtimeTranscriptionError.SessionUnavailable

                // Commit only when this socket received exactly the stable cache.
                // Any mismatch forces a fresh full replay before finalization.
                if (active.first.pendingCommitAudioBytes != cache.byteCount) {
                    recover(
                        RealtimeTranscriptionError.ConnectionLost(
                            "Audio not fully synced before finalize",
                        ),
                        expectedGeneration = active.second,
                    )
                    ensureSessionReadyForFinalize()
                    active = mutex.withLock {
                        session?.let { it to ownedGeneration }
                    }
                        ?: throw RealtimeTranscriptionError.SessionUnavailable
                    if (active.first.pendingCommitAudioBytes != cache.byteCount) {
                        throw RealtimeTranscriptionError.ConnectionLost(
                            "Audio byte count does not match cache before finalize",
                        )
                    }
                }

                try {
                    waitForFinalizeResult(active.first, active.second)
                    val resolved = mutex.withLock { finalizeText.resolvedText }
                    if (resolved.trim().isNotEmpty()) {
                        terminate(removeCache = true)
                        return resolved
                    }
                    lastError = RealtimeTranscriptionError.EmptyTranscript
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastError = error
                }

                if (attempt < maxAttempts - 1) {
                    recover(lastError, expectedGeneration = active.second)
                }
            }
            terminate(removeCache = false)
            throw lastError
        } finally {
            mutex.withLock {
                isFinalizing = false
                finalizeSignal = null
                finalizePartialCallback = null
            }
        }
    }

    override suspend fun cancel() {
        var sessionToClose: RealtimeWebSocketSession? = null
        val shouldRemoveCache = mutex.withLock {
            when (audioDisposition) {
                AudioDisposition.Preserved,
                AudioDisposition.Cancelled,
                -> false

                AudioDisposition.Active -> {
                    audioDisposition = AudioDisposition.Cancelled
                    isTerminated = true
                    ownedGeneration = null
                    sessionToClose = session
                    session = null
                    phase = RealtimeConnectionPhase.Disconnected
                    true
                }
            }
        }
        initialConnection.completeExceptionally(CancellationException("Session cancelled"))
        sessionToClose?.close()
        if (shouldRemoveCache) cache.remove()
    }

    override suspend fun abortPreservingAudio(): VoiceFlowPreservedAudio? {
        var sessionToClose: RealtimeWebSocketSession? = null
        var removeEmptyCache = false
        val preserved = mutex.withLock {
            when (audioDisposition) {
                AudioDisposition.Cancelled -> null
                AudioDisposition.Preserved -> preservedAudio
                AudioDisposition.Active -> {
                    val value = cache.preservedAudio(strategy, model)
                    audioDisposition = if (value == null) {
                        removeEmptyCache = true
                        AudioDisposition.Cancelled
                    } else {
                        preservedAudio = value
                        AudioDisposition.Preserved
                    }
                    isTerminated = true
                    ownedGeneration = null
                    sessionToClose = session
                    session = null
                    isRecovering = false
                    phase = RealtimeConnectionPhase.Disconnected
                    if (isFinalizing) {
                        completeFinalize(
                            Result.failure(
                                RealtimeTranscriptionError.ConnectionLost("Session aborted"),
                            ),
                        )
                    }
                    value
                }
            }
        }
        initialConnection.completeExceptionally(CancellationException("Session aborted"))
        sessionToClose?.close()
        if (removeEmptyCache) cache.remove()
        return preserved
    }

    private suspend fun terminate(removeCache: Boolean) {
        var sessionToClose: RealtimeWebSocketSession? = null
        mutex.withLock {
            isTerminated = true
            ownedGeneration = null
            sessionToClose = session
            session = null
            isRecovering = false
            phase = RealtimeConnectionPhase.Disconnected
            if (removeCache) audioDisposition = AudioDisposition.Cancelled
        }
        sessionToClose?.close()
        if (removeCache) cache.remove()
    }

    /** Receive one event only if its socket still owns this handle. */
    suspend fun ingestServerEvent(
        generation: Long,
        event: RealtimeTranscriptEvent,
    ): Boolean = handleServerEvent(generation, event)

    // --- internals ---------------------------------------------------------

    private suspend fun ensureSessionReadyForFinalize() {
        initialConnection.await()
        waitForRecovery()
        if (mutex.withLock { session == null }) {
            recover(
                RealtimeTranscriptionError.ConnectionLost("Session unavailable before finalize"),
            )
        }
        waitForRecovery()
        if (mutex.withLock { session == null }) {
            throw RealtimeTranscriptionError.SessionUnavailable
        }
    }

    /**
     * Send `commit` and race the resulting finalize signal against its deadline.
     * The signal is completed from [handleServerEvent] when the server reports
     * idle / disconnect / error.
     */
    private suspend fun waitForFinalizeResult(
        activeSession: RealtimeWebSocketSession,
        generation: Long?,
    ) {
        val signal = CompletableDeferred<Unit>()
        mutex.withLock {
            if (ownedGeneration != generation || session !== activeSession) {
                throw RealtimeTranscriptionError.SessionUnavailable
            }
            finalizeSignal = signal
        }
        activeSession.sendCommit()
        try {
            withTimeout(RealtimeTranscriptionConfig.finalizeTimeoutMs(strategy, cache.byteCount)) {
                signal.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            throw RealtimeTranscriptionError.ConnectionLost(
                "Timed out waiting for transcription to finish",
            )
        } finally {
            mutex.withLock { if (finalizeSignal === signal) finalizeSignal = null }
        }
    }

    private fun completeFinalize(result: Result<Unit>) {
        val signal = finalizeSignal ?: return
        finalizeSignal = null
        result
            .onSuccess { signal.complete(Unit) }
            .onFailure { signal.completeExceptionally(it) }
    }

    private suspend fun handleServerEvent(
        generation: Long,
        event: RealtimeTranscriptEvent,
    ): Boolean {
        var shouldRecover = false
        val shouldNotify = mutex.withLock {
            if (ownedGeneration != generation) return false
            when (event) {
                is RealtimeTranscriptEvent.Status -> {
                    when (event.status) {
                        RealtimeServerStatus.Connected, RealtimeServerStatus.Connecting ->
                            if (!isFinalizing) phase = RealtimeConnectionPhase.Connected

                        RealtimeServerStatus.Generating ->
                            phase = RealtimeConnectionPhase.Generating

                        RealtimeServerStatus.Idle -> {
                            phase = RealtimeConnectionPhase.Disconnected
                            if (isFinalizing) {
                                if (finalizeText.resolvedText.trim().isEmpty()) {
                                    completeFinalize(
                                        Result.failure(RealtimeTranscriptionError.EmptyTranscript),
                                    )
                                } else {
                                    completeFinalize(Result.success(Unit))
                                }
                            }
                        }
                    }
                    true
                }

                is RealtimeTranscriptEvent.Disconnected -> {
                    phase = RealtimeConnectionPhase.Disconnected
                    if (isFinalizing) {
                        completeFinalize(
                            Result.failure(
                                RealtimeTranscriptionError.ConnectionLost("WebSocket disconnected"),
                            ),
                        )
                    } else {
                        shouldRecover = true
                    }
                    true
                }

                is RealtimeTranscriptEvent.ErrorEvent -> {
                    val recoverable =
                        RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError(event.message)
                    if (isFinalizing) {
                        completeFinalize(
                            Result.failure(RealtimeTranscriptionError.WebsocketError(event.message)),
                        )
                    }
                    isFinalizing || !recoverable
                }

                is RealtimeTranscriptEvent.TextDelta -> {
                    if (isFinalizing && event.content.isNotEmpty()) {
                        if (event.isNewResponse) {
                            finalizeText.setCompleted(event.content)
                        } else {
                            finalizeText.appendDelta(event.content)
                        }
                        finalizePartialCallback?.invoke(finalizeText.resolvedText)
                    }
                    false
                }

                RealtimeTranscriptEvent.RecoveryStarted,
                is RealtimeTranscriptEvent.RecoveryFailed,
                -> true
            }
        }
        if (shouldRecover) {
            recover(
                RealtimeTranscriptionError.ConnectionLost("WebSocket disconnected"),
                expectedGeneration = generation,
            )
        }
        return shouldNotify
    }

    /**
     * Tear down the current socket and rebuild it, replaying the cache. Guarded so
     * concurrent failures don't stack. On exhaustion the phase goes Disconnected and
     * a `RecoveryFailed` event is emitted. Port of Swift `recover`.
     */
    private suspend fun recover(
        reason: Throwable,
        expectedGeneration: Long? = null,
    ) {
        if (mutex.withLock {
                audioDisposition != AudioDisposition.Active || isTerminated ||
                    (expectedGeneration != null && ownedGeneration != expectedGeneration)
            }
        ) {
            return
        }
        val oldSession = mutex.withLock {
            if (isRecovering || isTerminated ||
                audioDisposition != AudioDisposition.Active ||
                (expectedGeneration != null && ownedGeneration != expectedGeneration)
            ) {
                return
            }
            isRecovering = true
            phase = RealtimeConnectionPhase.Recovering
            val current = session
            session = null
            ownedGeneration = null
            current
        }
        onEvent(RealtimeTranscriptEvent.RecoveryStarted)
        Log.e(TAG, "Recovery begin bytes=${cache.byteCount}", reason)
        oldSession?.close()

        var lastError = reason
        for (attempt in 0 until RealtimeTranscriptionConfig.MAX_RECOVER_ATTEMPTS) {
            if (attempt > 0) {
                val delayMs =
                    RealtimeTranscriptionConfig.RECOVER_BACKOFF_BASE_MS.toLong() shl (attempt - 1)
                delay(delayMs)
            }
            try {
                val generation = mutex.withLock {
                    if (isTerminated || audioDisposition != AudioDisposition.Active) return
                    generationCounter += 1
                    generationCounter.also { ownedGeneration = it }
                }
                val replacement = makeSession(generation)
                try {
                    audioMutex.withLock {
                        replayCache(replacement)
                        mutex.withLock {
                            if (!isTerminated &&
                                audioDisposition == AudioDisposition.Active &&
                                ownedGeneration == generation
                            ) {
                                session = replacement
                                phase = if (isFinalizing) {
                                    RealtimeConnectionPhase.Generating
                                } else {
                                    RealtimeConnectionPhase.Connected
                                }
                            } else {
                                throw RealtimeTranscriptionError.SessionUnavailable
                            }
                            isRecovering = false
                        }
                    }
                } catch (error: Throwable) {
                    replacement.close()
                    throw error
                }
                Log.d(TAG, "Recovery done bytes=${cache.byteCount}")
                return
            } catch (error: CancellationException) {
                mutex.withLock {
                    isRecovering = false
                    ownedGeneration = null
                }
                throw error
            } catch (error: Exception) {
                mutex.withLock {
                    if (session == null) ownedGeneration = null
                }
                lastError = error
            }
        }

        mutex.withLock {
            phase = RealtimeConnectionPhase.Disconnected
            isRecovering = false
            ownedGeneration = null
        }
        onEvent(RealtimeTranscriptEvent.RecoveryFailed(lastError.toString()))
    }

    /**
     * Replay the disk cache into [targetSession] from byte 0. The caller holds
     * [audioMutex], so reaching [AudioChunkCache.byteCount] is a stable handoff point:
     * the replacement is installed before any later append can proceed.
     */
    private suspend fun replayCache(targetSession: RealtimeWebSocketSession) {
        var offset = 0
        while (true) {
            val chunk = cache.readChunk(offset, RealtimeTranscriptionConfig.REPLAY_CHUNK_SIZE)
            if (chunk.isEmpty()) {
                return
            }
            targetSession.sendAudioChunk(chunk)
            offset += chunk.size
        }
    }

    private suspend fun waitForRecovery() {
        while (mutex.withLock { isRecovering }) {
            delay(100)
        }
    }

    internal companion object {
        private const val TAG = "VFLiveSessionHandle"
        const val INITIAL_GENERATION = 1L
    }

    private enum class AudioDisposition {
        Active,
        Cancelled,
        Preserved,
    }
}
