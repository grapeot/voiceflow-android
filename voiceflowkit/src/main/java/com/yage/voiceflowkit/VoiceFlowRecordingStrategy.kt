package com.yage.voiceflowkit

/**
 * Selects the complete capture and transcription path for one recording.
 * Port of Swift `VoiceFlowRecordingStrategy`.
 */
enum class VoiceFlowRecordingStrategy {
    /** PCM16 / 24 kHz live WebSocket path. */
    OPENAI_REALTIME,

    /** Local capture during record; multipart file upload after Stop. */
    GROK_BATCH,
    ;

    /** True when this strategy opens a realtime session during capture. */
    val usesRealtimeTransport: Boolean
        get() = this == OPENAI_REALTIME

    companion object {
        fun fromRaw(raw: String?): VoiceFlowRecordingStrategy =
            when (raw) {
                GROK_BATCH.name, "grokBatch", "grok_batch" -> GROK_BATCH
                else -> OPENAI_REALTIME
            }
    }
}
