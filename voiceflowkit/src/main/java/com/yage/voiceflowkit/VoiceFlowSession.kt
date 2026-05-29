package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.RealtimeConnectionPhase
import com.yage.voiceflowkit.internal.RealtimeLiveTranscriptionSession
import com.yage.voiceflowkit.internal.RealtimeServerStatus
import com.yage.voiceflowkit.internal.RealtimeTranscriptEvent
import com.yage.voiceflowkit.internal.RealtimeTranscriptionError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A realtime transcription session. Push PCM chunks, optionally ping,
 * then [commitAndStop] to finalize. The session takes care of WS
 * reconnect + cache replay internally — [sendAudioChunk] never throws
 * because of network blips; it throws only if the session is already
 * cancelled or the disk cache write fails.
 *
 * [commitAndStop] returns the full transcript. The optional callback
 * fires repeatedly as partial deltas arrive during finalize.
 *
 * The Swift source models this as an `actor`; on Android the suspend
 * functions serialize through the underlying handle's own Mutex.
 */
class VoiceFlowSession internal constructor(
    private val underlying: RealtimeLiveTranscriptionSession,
    private val eventBridge: SessionEventBridge,
) {
    /**
     * Reactive event stream. Equivalent to the callback API; both can be
     * used. The backing [SharedFlow] buffers the newest 16 events (analog
     * of the Swift `AsyncStream.bufferingNewest(16)`), so start collecting
     * before/at session start to catch the earliest events.
     */
    val events: Flow<VoiceFlowEvent> get() = eventBridge.events

    /**
     * Push a PCM16 / 24kHz / mono chunk. The library buffers internally;
     * network state is hidden from the caller.
     */
    suspend fun sendAudioChunk(chunk: ByteArray) {
        underlying.appendAudioChunk(chunk)
    }

    /** Send a WebSocket ping. Host schedules cadence (VoiceFlow uses 12s). */
    suspend fun ping() {
        underlying.heartbeat()
    }

    /**
     * Commit the audio buffer, wait for the stop/idle event, and return the
     * final transcript. [onPartialTranscript] fires with the accumulated
     * text as deltas arrive. Internal errors are translated to
     * [VoiceFlowError] at this boundary.
     */
    suspend fun commitAndStop(onPartialTranscript: ((String) -> Unit)? = null): String {
        try {
            return underlying.finalize(onPartialTranscript)
        } catch (realtime: RealtimeTranscriptionError) {
            throw VoiceFlowError.from(realtime)
        }
    }

    /** Cancel without committing. Idempotent. */
    suspend fun cancel() {
        underlying.cancel()
    }

    /** Current connection phase (host uses this to drive UI). */
    suspend fun connectionPhase(): VoiceFlowConnectionPhase =
        underlying.connectionPhase().toPublic()
}

/**
 * Bridges internal [RealtimeTranscriptEvent] callbacks into the public
 * [Flow] of [VoiceFlowEvent]. The kit holds one bridge per session.
 *
 * Backed by a [MutableSharedFlow] with an extra buffer of 16 and
 * `DROP_OLDEST` overflow — the closest analog to Swift's
 * `AsyncStream(bufferingPolicy: .bufferingNewest(16))`. [emit] is a
 * non-suspending `tryEmit`, mirroring `continuation.yield`.
 */
internal class SessionEventBridge {
    private val _events = MutableSharedFlow<VoiceFlowEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: Flow<VoiceFlowEvent> = _events.asSharedFlow()

    /**
     * Map an internal event to one or more public events and publish them.
     * This mirrors the Swift `SessionEventBridge.emit` switch exactly:
     *  - textDelta            -> PartialTranscript
     *  - status               -> PhaseChanged(connected/connecting/generating/disconnected)
     *  - recoveryStarted      -> RecoveryStarted, then PhaseChanged(Recovering)
     *  - recoveryFailed       -> RecoveryFailed, then PhaseChanged(Disconnected)
     *  - error                -> RecoveryFailed (no phase change)
     *  - disconnected         -> PhaseChanged(Disconnected)
     */
    fun emit(event: RealtimeTranscriptEvent) {
        when (event) {
            is RealtimeTranscriptEvent.TextDelta ->
                publish(VoiceFlowEvent.PartialTranscript(event.content))

            is RealtimeTranscriptEvent.Status ->
                when (event.status) {
                    RealtimeServerStatus.Connected ->
                        publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Connected))
                    RealtimeServerStatus.Connecting ->
                        publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Connecting))
                    RealtimeServerStatus.Generating ->
                        publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Generating))
                    RealtimeServerStatus.Idle ->
                        publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Disconnected))
                }

            is RealtimeTranscriptEvent.RecoveryStarted -> {
                publish(VoiceFlowEvent.RecoveryStarted)
                publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Recovering))
            }

            is RealtimeTranscriptEvent.RecoveryFailed -> {
                publish(VoiceFlowEvent.RecoveryFailed(event.message))
                publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Disconnected))
            }

            is RealtimeTranscriptEvent.ErrorEvent ->
                publish(VoiceFlowEvent.RecoveryFailed(event.message))

            is RealtimeTranscriptEvent.Disconnected ->
                publish(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Disconnected))
        }
    }

    /** No-op finish hook kept for parity with the Swift continuation API. */
    fun finish() {
        // SharedFlow has no terminal signal; nothing to do. Method retained
        // so the client can call it on the error path symmetrically.
    }

    private fun publish(event: VoiceFlowEvent) {
        _events.tryEmit(event)
    }
}

/** Map the internal connection phase to the public enum. */
internal fun RealtimeConnectionPhase.toPublic(): VoiceFlowConnectionPhase = when (this) {
    RealtimeConnectionPhase.Connecting -> VoiceFlowConnectionPhase.Connecting
    RealtimeConnectionPhase.Connected -> VoiceFlowConnectionPhase.Connected
    RealtimeConnectionPhase.Recovering -> VoiceFlowConnectionPhase.Recovering
    RealtimeConnectionPhase.Generating -> VoiceFlowConnectionPhase.Generating
    RealtimeConnectionPhase.Disconnected -> VoiceFlowConnectionPhase.Disconnected
}
