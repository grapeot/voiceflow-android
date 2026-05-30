package com.yage.voiceflowkit.internal

import android.util.Log
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
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
 *   transcript, with a 30 s timeout and one automatic retry. The accumulated
 *   transcript is preserved across the retry so a transient drop doesn't lose text.
 *
 * Server events arrive through [ingestServerEvent] (wired by the owning client);
 * [shouldNotifyUI] replicates Swift's finalize-aware filtering so partial deltas
 * and recoverable "buffer too small" noise don't leak to the UI outside finalize.
 */
internal class RealtimeLiveSessionHandle(
    private val cache: AudioChunkCache,
    private val onEvent: (RealtimeTranscriptEvent) -> Unit,
    private val makeSession: suspend () -> RealtimeWebSocketSession,
) : RealtimeLiveTranscriptionSession {

    private val mutex = Mutex()
    private var session: RealtimeWebSocketSession? = null
    private var isRecovering = false
    private var phase: RealtimeConnectionPhase = RealtimeConnectionPhase.Connecting

    // Finalize state. Guarded by [mutex] for reads/writes that race with server events.
    private var isFinalizing = false
    private var finalizeSignal: CompletableDeferred<Unit>? = null
    private var finalizeText = FinalizeTranscriptAccumulator()
    private var finalizePartialCallback: ((String) -> Unit)? = null
    private var hasPreservedAudio = false

    override suspend fun connectionPhase(): RealtimeConnectionPhase = mutex.withLock { phase }

    /**
     * Initial connect path. Replays whatever is already cached into [newSession]
     * (normally nothing on a fresh start) and adopts it as the live session.
     * Mirrors Swift `attachInitialSession`.
     */
    suspend fun attachInitialSession(newSession: RealtimeWebSocketSession) {
        val shouldAttach = mutex.withLock {
            if (session != null || isRecovering) {
                false
            } else {
                isRecovering = true
                phase = RealtimeConnectionPhase.Recovering
                true
            }
        }
        if (!shouldAttach) {
            newSession.close()
            return
        }
        try {
            replayCache(newSession)
            mutex.withLock {
                session = newSession
                phase = RealtimeConnectionPhase.Connected
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Initial attach replay failed", error)
            newSession.close()
            onEvent(RealtimeTranscriptEvent.RecoveryFailed(error.toString()))
        } finally {
            mutex.withLock { isRecovering = false }
        }
    }

    override suspend fun appendAudioChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        if (mutex.withLock { hasPreservedAudio }) return
        cache.append(chunk)
        val activeSession = mutex.withLock { if (isRecovering) null else session } ?: return
        try {
            activeSession.sendAudioChunk(chunk)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recover(error)
        }
    }

    override suspend fun heartbeat() {
        val activeSession = mutex.withLock { if (isRecovering) null else session } ?: return
        try {
            activeSession.ping()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recover(error)
        }
    }

    /**
     * Commit the buffered audio and wait for the server to produce the final
     * transcript. Port of Swift `finalize`: two attempts, a per-attempt 30 s
     * timeout, an audio-sync sanity check, and transcript preservation between
     * attempts.
     */
    override suspend fun finalize(onPartialTranscript: ((String) -> Unit)?): String {
        mutex.withLock {
            isFinalizing = true
            finalizeText.reset()
            finalizePartialCallback = onPartialTranscript
            phase = RealtimeConnectionPhase.Generating
        }

        var lastError: Throwable = RealtimeTranscriptionError.EmptyTranscript
        try {
            val maxAttempts = 2
            for (attempt in 0 until maxAttempts) {
                ensureSessionReadyForFinalize()
                var activeSession = mutex.withLock { session }
                    ?: throw RealtimeTranscriptionError.SessionUnavailable

                // The cache holds enough audio to commit, but the *current socket*
                // never received it (e.g. it was opened during a recovery and the
                // replay is still catching up). Force a fresh recover+replay so the
                // server has the full stream before we commit.
                if (cache.byteCount >= RealtimeTranscriptionConfig.minCommitAudioBytes &&
                    activeSession.pendingCommitAudioBytes < RealtimeTranscriptionConfig.minCommitAudioBytes
                ) {
                    recover(
                        RealtimeTranscriptionError.ConnectionLost(
                            "Audio not fully synced before finalize",
                        ),
                    )
                    ensureSessionReadyForFinalize()
                    activeSession = mutex.withLock { session }
                        ?: throw RealtimeTranscriptionError.SessionUnavailable
                }

                try {
                    waitForFinalizeResult(activeSession)
                    val resolved = mutex.withLock { finalizeText.resolvedText }
                    if (resolved.trim().isNotEmpty()) {
                        return resolved
                    }
                    lastError = RealtimeTranscriptionError.EmptyTranscript
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastError = error
                }

                if (attempt < maxAttempts - 1) {
                    val preserved = mutex.withLock { finalizeText.preserveForRetry() }
                    recover(lastError)
                    if (preserved.trim().isNotEmpty()) {
                        mutex.withLock { finalizeText.restoreAfterRetry(preserved) }
                    }
                }
            }
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
        val shouldRemoveCache = mutex.withLock { !hasPreservedAudio }
        mutex.withLock {
            session?.close()
            session = null
            phase = RealtimeConnectionPhase.Disconnected
        }
        if (shouldRemoveCache) cache.remove()
    }

    override suspend fun abortPreservingAudio(): VoiceFlowPreservedAudio? {
        mutex.withLock {
            session?.close()
            session = null
            isRecovering = false
            phase = RealtimeConnectionPhase.Disconnected
            if (isFinalizing) {
                completeFinalize(Result.failure(RealtimeTranscriptionError.ConnectionLost("Session aborted")))
            }
        }
        val preserved = cache.preservedAudio()
        if (preserved == null) {
            cache.remove()
            return null
        }
        mutex.withLock { hasPreservedAudio = true }
        return preserved
    }

    /** Receive a server event for state bookkeeping (called by the owning client). */
    suspend fun ingestServerEvent(event: RealtimeTranscriptEvent) {
        handleServerEvent(event)
    }

    /**
     * Whether [event] should be forwarded to the UI. Port of Swift `shouldNotifyUI`:
     * text deltas only surface during finalize; recoverable "buffer too small"
     * errors are suppressed unless finalizing.
     */
    suspend fun shouldNotifyUI(event: RealtimeTranscriptEvent): Boolean = mutex.withLock {
        when (event) {
            is RealtimeTranscriptEvent.TextDelta -> isFinalizing
            is RealtimeTranscriptEvent.ErrorEvent ->
                isFinalizing ||
                    !RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError(event.message)
            else -> true
        }
    }

    // --- internals ---------------------------------------------------------

    private suspend fun ensureSessionReadyForFinalize() {
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
     * Send `commit` and race the resulting finalize signal against a 30 s timeout.
     * The signal is completed from [handleServerEvent] when the server reports
     * idle / disconnect / error.
     */
    private suspend fun waitForFinalizeResult(activeSession: RealtimeWebSocketSession) {
        val signal = CompletableDeferred<Unit>()
        mutex.withLock { finalizeSignal = signal }
        activeSession.sendCommit()
        try {
            withTimeout(RealtimeTranscriptionConfig.FINALIZE_TIMEOUT_MS) {
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

    private suspend fun handleServerEvent(event: RealtimeTranscriptEvent) {
        when (event) {
            is RealtimeTranscriptEvent.Status -> when (event.status) {
                RealtimeServerStatus.Connected, RealtimeServerStatus.Connecting ->
                    mutex.withLock { if (!isFinalizing) phase = RealtimeConnectionPhase.Connected }

                RealtimeServerStatus.Generating ->
                    mutex.withLock { phase = RealtimeConnectionPhase.Generating }

                RealtimeServerStatus.Idle -> mutex.withLock {
                    phase = RealtimeConnectionPhase.Disconnected
                    if (isFinalizing) {
                        if (finalizeText.resolvedText.trim().isEmpty()) {
                            completeFinalize(Result.failure(RealtimeTranscriptionError.EmptyTranscript))
                        } else {
                            completeFinalize(Result.success(Unit))
                        }
                    }
                }
            }

            is RealtimeTranscriptEvent.Disconnected -> {
                val shouldRecover = mutex.withLock {
                    phase = RealtimeConnectionPhase.Disconnected
                    if (isFinalizing) {
                        completeFinalize(
                            Result.failure(
                                RealtimeTranscriptionError.ConnectionLost("WebSocket disconnected"),
                            ),
                        )
                        false
                    } else {
                        true
                    }
                }
                if (shouldRecover) {
                    recover(RealtimeTranscriptionError.ConnectionLost("WebSocket disconnected"))
                }
            }

            is RealtimeTranscriptEvent.ErrorEvent -> mutex.withLock {
                val recoverable =
                    RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError(event.message)
                if (recoverable && !isFinalizing) {
                    // Swallow: a transient "buffer too small" outside finalize is noise.
                } else if (isFinalizing) {
                    completeFinalize(
                        Result.failure(RealtimeTranscriptionError.WebsocketError(event.message)),
                    )
                }
            }

            is RealtimeTranscriptEvent.TextDelta -> {
                val callback: ((String) -> Unit)?
                val snapshot: String?
                mutex.withLock {
                    if (!isFinalizing || event.content.isEmpty()) {
                        callback = null
                        snapshot = null
                    } else {
                        if (event.isNewResponse) {
                            finalizeText.setCompleted(event.content)
                        } else {
                            finalizeText.appendDelta(event.content)
                        }
                        snapshot = finalizeText.resolvedText
                        callback = finalizePartialCallback
                    }
                }
                if (callback != null && snapshot != null) callback(snapshot)
            }

            RealtimeTranscriptEvent.RecoveryStarted,
            is RealtimeTranscriptEvent.RecoveryFailed,
            -> Unit
        }
    }

    /**
     * Tear down the current socket and rebuild it, replaying the cache. Guarded so
     * concurrent failures don't stack. On exhaustion the phase goes Disconnected and
     * a `RecoveryFailed` event is emitted. Port of Swift `recover`.
     */
    private suspend fun recover(reason: Throwable) {
        if (mutex.withLock { hasPreservedAudio }) return
        val oldSession = mutex.withLock {
            if (isRecovering) return
            isRecovering = true
            phase = RealtimeConnectionPhase.Recovering
            val current = session
            session = null
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
                val replacement = makeSession()
                replayCache(replacement)
                mutex.withLock {
                    session = replacement
                    phase = RealtimeConnectionPhase.Connected
                    isRecovering = false
                }
                Log.d(TAG, "Recovery done bytes=${cache.byteCount}")
                return
            } catch (error: CancellationException) {
                mutex.withLock { isRecovering = false }
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }

        mutex.withLock {
            phase = RealtimeConnectionPhase.Disconnected
            isRecovering = false
        }
        onEvent(RealtimeTranscriptEvent.RecoveryFailed(lastError.toString()))
    }

    /**
     * Replay the disk cache into [targetSession] from byte 0. Reads
     * [RealtimeTranscriptionConfig.REPLAY_CHUNK_SIZE] windows; when it catches up to
     * the live tail it waits briefly for more audio rather than returning, matching
     * Swift's `replayCache` (which only returns once `offset >= byteCount`).
     */
    private suspend fun replayCache(targetSession: RealtimeWebSocketSession) {
        var offset = 0
        while (true) {
            val chunk = cache.readChunk(offset, RealtimeTranscriptionConfig.REPLAY_CHUNK_SIZE)
            if (chunk.isEmpty()) {
                if (offset >= cache.byteCount) return
                delay(20)
                continue
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

    private companion object {
        private const val TAG = "VFLiveSessionHandle"
    }
}
