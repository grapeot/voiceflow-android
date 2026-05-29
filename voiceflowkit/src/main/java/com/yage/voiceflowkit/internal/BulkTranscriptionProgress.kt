package com.yage.voiceflowkit.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aggregator for the WebSocket event stream during a one-shot bulk transcribe.
 *
 * Port of Swift `BulkTranscriptionProgress`. Uses a [Mutex] in place of the Swift
 * actor's isolation.
 *
 * The critical correctness fix (Swift PR #34) lives here: once the server has
 * reported `Status(Idle)` — i.e. transcription is complete — any subsequent
 * `Disconnected` / `ErrorEvent` events are just the socket winding down on the way
 * home and MUST NOT be treated as failures, and any trailing `TextDelta` must be
 * ignored so it cannot corrupt the already-final value. Without this, a resend
 * would report "transcription failed" right after successfully delivering text.
 */
internal class BulkTranscriptionProgress {
    private val mutex = Mutex()
    private var transcriptValue = ""
    private var finishedValue = false
    private var receivedErrorValue: String? = null

    suspend fun handle(
        event: RealtimeTranscriptEvent,
        onPartialTranscript: ((String) -> Unit)?,
    ) {
        val partialToEmit: String? = mutex.withLock {
            if (finishedValue) {
                // Already done: ignore trailing deltas/disconnect/error entirely.
                return@withLock null
            }
            when (event) {
                is RealtimeTranscriptEvent.TextDelta -> {
                    transcriptValue = TranscriptDeltaReducer.apply(
                        current = transcriptValue,
                        content = event.content,
                        isNewResponse = event.isNewResponse,
                    )
                    transcriptValue
                }

                is RealtimeTranscriptEvent.Status -> {
                    if (event.status == RealtimeServerStatus.Idle) {
                        finishedValue = true
                    }
                    null
                }

                is RealtimeTranscriptEvent.ErrorEvent -> {
                    receivedErrorValue = event.message
                    finishedValue = true
                    null
                }

                is RealtimeTranscriptEvent.Disconnected -> {
                    receivedErrorValue = "WebSocket disconnected"
                    finishedValue = true
                    null
                }

                RealtimeTranscriptEvent.RecoveryStarted,
                is RealtimeTranscriptEvent.RecoveryFailed,
                -> null
            }
        }
        if (partialToEmit != null) onPartialTranscript?.invoke(partialToEmit)
    }

    suspend fun transcript(): String = mutex.withLock { transcriptValue }

    suspend fun isFinished(): Boolean = mutex.withLock { finishedValue }

    suspend fun receivedError(): String? = mutex.withLock { receivedErrorValue }
}
