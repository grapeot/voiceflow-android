package com.yage.voiceflowkit

/**
 * Fetches the Bearer token used to authenticate each request.
 *
 * Port of the Swift `@Sendable () async throws -> String` closure. The
 * provider can re-read secure storage on every call (host always observes
 * the latest saved token) or capture a snapshot (host pins one token per
 * session). Both are valid; pick based on the user experience you want when
 * the token changes mid-session. Throwing from here surfaces as a token
 * failure at the facade boundary.
 */
typealias TokenProvider = suspend () -> String

/**
 * Configuration for [VoiceFlowClient]. Pass a fresh config any time the
 * underlying settings (endpoint, token, prompt/terms) change; the next
 * session/transcribe call picks it up.
 *
 * Port of the Swift `VoiceFlowConfig` struct. Notable Android-specific
 * differences from the Swift source:
 *  - `endpoint` is a `String` (not a `URL`). URL validation/normalization
 *    happens internally in `RealtimeApiUrlBuilder`, matching the proven
 *    opencode Android reference which carries the base URL as a string.
 *  - The Swift `loggerSubsystem` field is dropped: Android has no OSLog;
 *    transport-level logging uses `android.util.Log` tags instead.
 */
data class VoiceFlowConfig(
    /**
     * AI Builder Space backend base URL. Defaults to
     * [DEFAULT_ENDPOINT]. Sessions POST to
     * `{endpoint}/v1/audio/realtime/sessions` to obtain a WS ticket.
     */
    val endpoint: String = DEFAULT_ENDPOINT,
    /**
     * Called once per request to fetch the Bearer token. See [TokenProvider].
     */
    val tokenProvider: TokenProvider,
    /**
     * OpenAI realtime model name. Defaults to [DEFAULT_MODEL]. Changing this
     * only matters if AI Builder Space rolls out additional realtime models —
     * the wire protocol is shared.
     */
    val model: String = DEFAULT_MODEL,
    /**
     * Optional context prompt for the transcription model. The backend treats
     * this as prompt concatenation, so the host is free to embed any context —
     * including language hints (e.g. "User is speaking Mandarin Chinese") —
     * directly in the prompt string. The kit deliberately does not expose a
     * separate `language` field to avoid duplicating a knob the backend
     * doesn't have.
     */
    val prompt: String? = null,
    /**
     * Domain-specific terms the recognizer should preserve. Stored as a list
     * here even though the wire format may concatenate them into the prompt —
     * keeps the host API ergonomic.
     */
    val terms: List<String> = emptyList(),
) {
    companion object {
        /** Default AI Builder Space backend base URL. */
        const val DEFAULT_ENDPOINT: String = "https://space.ai-builders.com/backend"

        /** Default OpenAI realtime model name. */
        const val DEFAULT_MODEL: String = "gpt-realtime"
    }
}
