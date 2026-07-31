package com.yage.voiceflowkit.internal

/**
 * Wire-protocol constants for the realtime transcription pipeline.
 *
 * Port of Swift `RealtimeTranscriptionConfig` (Internal/RealtimeTranscriptEvent.swift)
 * reconciled with the proven Android reference `AudioTranscriptionConfig`
 * (grapeot/opencode_android_client). Values here MUST match the backend contract exactly;
 * the backend was confirmed working with these on Android.
 */
internal object RealtimeTranscriptionConfig {
    /** Default realtime model identifier. */
    const val DEFAULT_MODEL: String = "gpt-realtime"

    /** Capture/playback sample rate in Hz (PCM16 mono). */
    const val SAMPLE_RATE: Int = 24_000

    /** Duration of a single capture chunk before it is flushed to the wire. */
    const val CHUNK_DURATION_SECONDS: Double = 0.5

    /** Window size used when replaying the disk cache after a recovery. */
    const val REPLAY_CHUNK_SIZE: Int = 240_000

    /** WebSocket heartbeat / OkHttp ping interval, in seconds. */
    const val HEARTBEAT_INTERVAL_SECONDS: Long = 12L

    /** REST path used to create a realtime session. */
    const val SESSION_CREATE_PATH: String = "/v1/audio/realtime/sessions"

    /** Text control frame that asks the server to commit the buffered audio. */
    const val COMMIT_MESSAGE: String = "{\"type\":\"commit\"}"

    /** Text control frame that asks the server to stop the session. */
    const val STOP_MESSAGE: String = "{\"type\":\"stop\"}"

    /** Maximum number of reconnect attempts before a recovery is declared failed. */
    const val MAX_RECOVER_ATTEMPTS: Int = 5

    /** Base backoff in milliseconds; attempt N waits base * 2^(N-1). */
    const val RECOVER_BACKOFF_BASE_MS: Int = 300

    /** Server-side silence threshold passed in session-create / start control. */
    const val SILENCE_DURATION_MS: Int = 1_200

    /** Upper bound for awaiting a finalize result (commit -> completed -> stopped). */
    const val FINALIZE_TIMEOUT_MS: Long = 30_000L

    /** PCM16 mono 24 kHz bytes per second. */
    const val PCM_BYTES_PER_SECOND: Long = SAMPLE_RATE * 2L

    private const val GPT_LIVE_MIN_TIMEOUT_MS: Long = 60_000L
    private const val GPT_LIVE_TIMEOUT_MARGIN_MS: Long = 60_000L

    /** Content-Type for JSON request bodies. */
    const val JSON_MEDIA_TYPE: String = "application/json; charset=utf-8"

    /** OkHttp connect timeout (seconds), from the proven reference. */
    const val CONNECT_TIMEOUT_SECONDS: Long = 15L

    /** OkHttp read timeout (seconds), from the proven reference. */
    const val READ_TIMEOUT_SECONDS: Long = 60L

    /** OkHttp write timeout (seconds), from the proven reference. */
    const val WRITE_TIMEOUT_SECONDS: Long = 60L

    /**
     * Bytes per capture chunk: `(SAMPLE_RATE * CHUNK_DURATION_SECONDS) * 2`.
     * At 24 kHz / 0.5 s / 16-bit mono this is 24000 bytes.
     */
    val chunkByteSize: Int
        get() = (SAMPLE_RATE * CHUNK_DURATION_SECONDS).toInt() * 2

    /**
     * Minimum PCM16 mono audio required before sending `commit` (100 ms at 24 kHz).
     * `(SAMPLE_RATE * 0.1) * 2` = 4800 bytes.
     */
    val minCommitAudioBytes: Int
        get() = (SAMPLE_RATE * 0.1).toInt() * 2

    fun finalizeTimeoutMs(
        strategy: com.yage.voiceflowkit.VoiceFlowRecordingStrategy,
        pcmBytes: Int,
    ): Long {
        if (strategy != com.yage.voiceflowkit.VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) {
            return FINALIZE_TIMEOUT_MS
        }
        val audioDurationMs = pcmBytes.toLong().coerceAtLeast(0L) * 1_000L / PCM_BYTES_PER_SECOND
        return maxOf(GPT_LIVE_MIN_TIMEOUT_MS, audioDurationMs + GPT_LIVE_TIMEOUT_MARGIN_MS)
    }
}
