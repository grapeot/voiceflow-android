package com.yage.voiceflowkit

/**
 * Public connection phase exposed by [VoiceFlowSession]. Mirrors the
 * internal `RealtimeConnectionPhase` but hosts don't need to import the
 * internal type.
 *
 * Port of the Swift `VoiceFlowConnectionPhase` enum. The internal ->
 * public mapping (`RealtimeConnectionPhase` -> [VoiceFlowConnectionPhase])
 * is performed in the session facade layer, not here, to keep this file
 * free of internal dependencies.
 */
enum class VoiceFlowConnectionPhase {
    Connecting,
    Connected,
    Recovering,
    Generating,
    Disconnected,
}

/**
 * Public reactive event stream exposed by `VoiceFlowSession.events`.
 *
 * Port of the Swift `VoiceFlowEvent` enum (associated-value enum cases
 * become Kotlin sealed-class subtypes). The Swift `SessionEventBridge`
 * maps internal `RealtimeTranscriptEvent` values onto these; that bridge
 * lives in the session facade layer. For reference, the intended mapping
 * (which the bridge layer must reproduce) is:
 *  - `textDelta(content, _)`        -> [PartialTranscript]
 *  - `status(.connected)`           -> [PhaseChanged] (Connected)
 *  - `status(.connecting)`          -> [PhaseChanged] (Connecting)
 *  - `status(.generating)`          -> [PhaseChanged] (Generating)
 *  - `status(.idle)`                -> [PhaseChanged] (Disconnected)
 *  - `recoveryStarted`              -> [RecoveryStarted] then [PhaseChanged] (Recovering)
 *  - `recoveryFailed(message)`      -> [RecoveryFailed] then [PhaseChanged] (Disconnected)
 *  - `error(message)`               -> [RecoveryFailed] (no phase change)
 *  - `disconnected`                 -> [PhaseChanged] (Disconnected)
 */
sealed class VoiceFlowEvent {
    /** A partial (or final) transcript delta as recognition proceeds. */
    data class PartialTranscript(val text: String) : VoiceFlowEvent()

    /** The connection phase changed. Hosts use this to drive UI. */
    data class PhaseChanged(val phase: VoiceFlowConnectionPhase) : VoiceFlowEvent()

    /** Reconnection has begun after a transport blip. */
    data object RecoveryStarted : VoiceFlowEvent()

    /** Reconnection exhausted its attempts (or a non-finalize error). */
    data class RecoveryFailed(val message: String) : VoiceFlowEvent()
}
