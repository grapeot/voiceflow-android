package com.yage.voiceflowkit

/**
 * Public error model exposed by [VoiceFlowClient] / [VoiceFlowSession] /
 * [VoiceFlowMicrophone]. The internal `RealtimeTranscriptionError` and the
 * other typed errors are translated into this sealed class at the facade
 * boundary so external callers don't depend on internal types.
 *
 * Port of the Swift `VoiceFlowError` enum. Swift `Error` conformance maps to
 * extending [Exception] so instances can be `throw`n idiomatically on the
 * JVM. Associated-value cases become `data class` subtypes; value-less cases
 * become `data object` singletons.
 *
 * The internal -> public translation (Swift's `init(_ realtime:)`) lives in
 * the facade layer alongside the internal `RealtimeTranscriptionError`
 * definition, so this file stays dependency-free. For reference, the
 * intended mapping the facade must reproduce is:
 *  - `invalidBaseURL`        -> [InvalidEndpoint]
 *  - `missingToken`          -> [MissingToken]
 *  - `invalidMessage`        -> `WebsocketError("Invalid server message")`
 *  - `connectionLost(d)`     -> `ConnectionLost(d)`
 *  - `websocketError(d)`     -> `WebsocketError(d)`
 *  - `sessionUnavailable`    -> [SessionUnavailable]
 *  - `emptyTranscript`       -> [EmptyTranscript]
 *  - `audioConversionFailed` -> [AudioConversionFailed]
 *  - `httpError(code)`       -> `HttpError(code)`
 */
sealed class VoiceFlowError(message: String? = null) : Exception(message) {
    /** The configured endpoint could not be parsed into a valid URL. */
    data object InvalidEndpoint : VoiceFlowError("Invalid endpoint")

    /** The token provider returned an empty/blank token. */
    data object MissingToken : VoiceFlowError("Missing token")

    /** The backend returned a non-2xx HTTP status during session create. */
    data class HttpError(val statusCode: Int) : VoiceFlowError("HTTP error $statusCode")

    /** The session could not be established (no WS URL / ticket). */
    data object SessionUnavailable : VoiceFlowError("Session unavailable")

    /** A WebSocket-level protocol or transport error. */
    data class WebsocketError(val detail: String) : VoiceFlowError(detail)

    /** The connection dropped and could not be recovered. */
    data class ConnectionLost(val detail: String) : VoiceFlowError(detail)

    /** A WAV/PCM conversion failed (e.g. malformed input file). */
    data object AudioConversionFailed : VoiceFlowError("Audio conversion failed")

    /** Finalize completed but produced no transcript text. */
    data object EmptyTranscript : VoiceFlowError("Empty transcript")

    /** Microphone capture could not start (permission/hardware). */
    data object MicrophoneUnavailable : VoiceFlowError("Microphone unavailable")

    /** Any other underlying failure, carried as a detail string. */
    data class Underlying(val detail: String) : VoiceFlowError(detail)

    companion object {
        /**
         * Translate an internal [com.yage.voiceflowkit.internal.RealtimeTranscriptionError]
         * into the public error model. Parallels Swift `VoiceFlowError.init(_ realtime:)`.
         */
        internal fun from(
            error: com.yage.voiceflowkit.internal.RealtimeTranscriptionError,
        ): VoiceFlowError = when (error) {
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.InvalidBaseUrl -> InvalidEndpoint
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.MissingToken -> MissingToken
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.InvalidMessage ->
                WebsocketError("Invalid server message")
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.ConnectionLost ->
                ConnectionLost(error.detail)
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.WebsocketError ->
                WebsocketError(error.detail)
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.SessionUnavailable -> SessionUnavailable
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.EmptyTranscript -> EmptyTranscript
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.AudioConversionFailed ->
                AudioConversionFailed
            is com.yage.voiceflowkit.internal.RealtimeTranscriptionError.HttpError ->
                HttpError(error.statusCode)
        }
    }
}
