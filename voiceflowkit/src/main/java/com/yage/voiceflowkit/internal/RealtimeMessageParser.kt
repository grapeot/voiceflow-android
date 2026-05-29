package com.yage.voiceflowkit.internal

import org.json.JSONObject

/**
 * Decoded view of one inbound WebSocket frame.
 *
 * Mirrors Swift `RealtimeSocketEvent` and the proven Android `RealtimeSocketEvent`:
 * `text` falls back to `content` so both server payload shapes parse identically.
 */
internal data class RealtimeSocketEvent(
    val type: String,
    val text: String?,
    val code: String?,
    val message: String?,
) {
    /**
     * Builds a socket event from a parsed JSON object.
     * Empty string fields collapse to `null` to match the proven reference's `ifEmpty { null }`.
     */
    constructor(json: JSONObject) : this(
        type = json.optString("type"),
        // text falls back to content (Swift: `json["text"] ?? json["content"]`).
        text = json.optString("text").ifEmpty { json.optString("content") }.ifEmpty { null },
        code = json.optString("code").ifEmpty { null },
        message = json.optString("message").ifEmpty { null },
    )
}

/**
 * Translates inbound socket frames into internal [RealtimeTranscriptEvent]s and builds the
 * outbound `start` control frame.
 *
 * Port of Swift `RealtimeMessageParser`. Uses `org.json` to match the proven Android reference.
 */
internal object RealtimeMessageParser {
    /**
     * Maps a raw inbound JSON frame to an internal event, or `null` for frames the pipeline
     * deliberately ignores (unknown types, empty-text deltas).
     */
    fun parseSocketEvent(json: JSONObject): RealtimeTranscriptEvent? =
        parseSocketEvent(RealtimeSocketEvent(json))

    /**
     * Maps a decoded [RealtimeSocketEvent] to an internal event. Mirrors the Swift switch:
     * - `session_ready` / `speech_started` / `speech_stopped` -> [RealtimeServerStatus.Connected]
     * - `transcript_delta` -> [RealtimeTranscriptEvent.TextDelta] (append; null if empty)
     * - `transcript_completed` -> [RealtimeTranscriptEvent.TextDelta] (replace; null if empty)
     * - `session_stopped` -> [RealtimeServerStatus.Idle]
     * - `error` -> [RealtimeTranscriptEvent.ErrorEvent] (message ?: code ?: fallback)
     * - anything else -> `null`
     */
    fun parseSocketEvent(event: RealtimeSocketEvent): RealtimeTranscriptEvent? {
        return when (event.type) {
            "session_ready" -> RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected)
            "speech_started", "speech_stopped" ->
                RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected)
            "transcript_delta" -> {
                val content = event.text.orEmpty()
                if (content.isEmpty()) null
                else RealtimeTranscriptEvent.TextDelta(content = content, isNewResponse = false)
            }
            "transcript_completed" -> {
                val content = event.text.orEmpty()
                if (content.isEmpty()) null
                else RealtimeTranscriptEvent.TextDelta(content = content, isNewResponse = true)
            }
            "session_stopped" -> RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle)
            "error" -> {
                val message = event.message ?: event.code ?: "Unknown websocket error"
                RealtimeTranscriptEvent.ErrorEvent(message = message)
            }
            else -> null
        }
    }

    /**
     * Builds the `{"type":"start", ...}` control frame sent right after `session_ready`.
     *
     * Default `vad = false` matches the wire protocol used by this library (the Swift default
     * of `true` is overridden by callers); `silenceDurationMs` defaults to the configured value.
     */
    fun startControlMessage(
        model: String,
        vad: Boolean = false,
        silenceDurationMs: Int = RealtimeTranscriptionConfig.SILENCE_DURATION_MS,
    ): String {
        return JSONObject()
            .put("type", "start")
            .put("model", model)
            .put("vad", vad)
            .put("silence_duration_ms", silenceDurationMs)
            .toString()
    }
}
