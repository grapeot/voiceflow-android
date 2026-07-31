package com.yage.voiceflowkit.internal

/**
 * Pure transcript-reduction helpers.
 *
 * Ports of Swift `TranscriptDeltaReducer` and `RealtimeTranscriptionSupport`
 * (Internal/RealtimeTranscriptEvent.swift). These are stateless and side-effect-free so they
 * can be unit-tested in isolation.
 *
 * NOTE FOR INTEGRATION: the live-session orchestrator layer also plans to host a stateful
 * `FinalizeTranscriptAccumulator` in this same file (per the design's internal files plan). It
 * is intentionally NOT defined here — this layer (wire/transport) owns only the two stateless
 * objects below. The orchestrator layer should append `FinalizeTranscriptAccumulator` to this
 * file, reusing [TranscriptDeltaReducer] and [RealtimeTranscriptionSupport].
 */

/**
 * Reduces a stream of transcript deltas into the current transcript.
 *
 * Mirrors Swift `TranscriptDeltaReducer.apply`: a `transcript_completed` frame
 * ([isNewResponse] = true) replaces the accumulated text; a `transcript_delta`
 * ([isNewResponse] = false) appends.
 */
internal object TranscriptDeltaReducer {
    fun apply(current: String, content: String, isNewResponse: Boolean): String {
        return if (isNewResponse) content else current + content
    }
}

/**
 * Cross-cutting support predicates for the realtime pipeline.
 *
 * Port of Swift `RealtimeTranscriptionSupport`.
 */
internal object RealtimeTranscriptionSupport {
    /**
     * Whether a server error message indicates a recoverable "buffer too small" condition — i.e.
     * the server complained that too little audio was committed. The orchestrator suppresses these
     * outside of finalize. Case-insensitive, matching Swift's `localizedCaseInsensitiveContains`.
     */
    fun isRecoverableBufferTooSmallError(message: String): Boolean {
        return message.contains("buffer too small", ignoreCase = true)
    }

    /**
     * Picks the transcript to surface at finalize given the live [partial] and the server's
     * [completed] payload.
     *
     * A nonblank `transcript_completed` payload is authoritative. Partial text is
     * used only until completion or when the completion payload is blank.
     */
    fun resolveFinalizeTranscript(partial: String, completed: String?): String {
        val trimmedPartial = partial.trim()
        val trimmedCompleted = completed?.trim().orEmpty()
        return if (trimmedCompleted.isNotEmpty()) trimmedCompleted else partial
    }
}

/**
 * Mutable accumulator for the transcript produced during `finalize`.
 *
 * Port of Swift `FinalizeTranscriptAccumulator` (Internal/RealtimeTranscriptionClient.swift).
 * Owned by the orchestrator layer ([RealtimeLiveSessionHandle]); the type layer above
 * delegated it here. It keeps the running `partial` text (from incremental
 * `transcript_delta` frames) separate from the server's authoritative `completed`
 * payload (from `transcript_completed`), and resolves the two via
 * [RealtimeTranscriptionSupport.resolveFinalizeTranscript].
 *
 * Not thread-safe on its own — the handle serializes all access through its `Mutex`.
 */
internal class FinalizeTranscriptAccumulator {
    private var partialText: String = ""
    private var completedText: String? = null

    /** The transcript to surface right now, reconciling partial vs completed. */
    val resolvedText: String
        get() = RealtimeTranscriptionSupport.resolveFinalizeTranscript(partialText, completedText)

    /** Clear all state before a fresh finalize attempt. */
    fun reset() {
        partialText = ""
        completedText = null
    }

    /** Append one incremental delta to the running partial text. */
    fun appendDelta(content: String) {
        partialText += content
    }

    /** Record the server's authoritative completed payload. */
    fun setCompleted(content: String) {
        completedText = content
    }

}
