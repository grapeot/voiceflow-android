package com.yage.voiceflowkit.internal

/**
 * Internal type layer for the realtime transcription pipeline.
 *
 * Port of Swift `Internal/RealtimeTranscriptEvent.swift`. None of these types are part
 * of the published ABI; the public facade ([com.yage.voiceflowkit.VoiceFlowEvent],
 * [com.yage.voiceflowkit.VoiceFlowError], etc.) translates to/from them at the boundary.
 */

/** Coarse server-reported lifecycle status. Mirrors Swift `RealtimeServerStatus`. */
internal enum class RealtimeServerStatus {
    Idle,
    Connecting,
    Connected,
    Generating,
}

/**
 * Events surfaced from a live or bulk realtime session.
 *
 * Mirrors Swift `RealtimeTranscriptEvent`. [TextDelta.isNewResponse] distinguishes a
 * fresh `transcript_completed` payload (replace) from an incremental `transcript_delta`
 * (append) — see [TranscriptDeltaReducer].
 */
internal sealed class RealtimeTranscriptEvent {
    data class Status(val status: RealtimeServerStatus) : RealtimeTranscriptEvent()
    data class TextDelta(val content: String, val isNewResponse: Boolean) : RealtimeTranscriptEvent()
    data class ErrorEvent(val message: String) : RealtimeTranscriptEvent()
    data object Disconnected : RealtimeTranscriptEvent()
    data object RecoveryStarted : RealtimeTranscriptEvent()
    data class RecoveryFailed(val message: String) : RealtimeTranscriptEvent()
}

/** Connection phase tracked by the live-session orchestrator. Mirrors Swift `RealtimeConnectionPhase`. */
internal enum class RealtimeConnectionPhase {
    Disconnected,
    Connecting,
    Connected,
    Generating,
    Recovering,
}

/**
 * Internal error model for the transport pipeline. Mirrors Swift `RealtimeTranscriptionError`.
 *
 * The public facade maps these to [com.yage.voiceflowkit.VoiceFlowError] at the boundary
 * (parallels Swift `VoiceFlowError.init(_:)`).
 */
internal sealed class RealtimeTranscriptionError(detail: String? = null) : Exception(detail) {
    data object InvalidBaseUrl : RealtimeTranscriptionError("Invalid base URL")
    data object MissingToken : RealtimeTranscriptionError("Missing token")
    data object InvalidMessage : RealtimeTranscriptionError("Invalid message")
    data class ConnectionLost(val detail: String) : RealtimeTranscriptionError(detail)
    data class WebsocketError(val detail: String) : RealtimeTranscriptionError(detail)
    data object SessionUnavailable : RealtimeTranscriptionError("Session unavailable")
    data object EmptyTranscript : RealtimeTranscriptionError("Empty transcript")
    data object AudioConversionFailed : RealtimeTranscriptionError("Audio conversion failed")
    data class HttpError(val statusCode: Int) : RealtimeTranscriptionError("HTTP error $statusCode")
}

/**
 * Optional per-session context forwarded into session-create.
 *
 * Mirrors the `prompt` / `terms` fields the Swift session-create body carries.
 */
internal data class RealtimeSessionContext(
    val prompt: String? = null,
    val terms: List<String> = emptyList(),
)
