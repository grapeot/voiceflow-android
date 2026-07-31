package com.yage.voiceflowkit

/**
 * Selects the complete capture and transcription path for one recording.
 * Port of Swift `VoiceFlowRecordingStrategy`.
 */
enum class VoiceFlowRecordingStrategy {
    /** PCM16 / 24 kHz live WebSocket path. */
    OPENAI_REALTIME,

    /** PCM16 / 24 kHz live WebSocket path using GPT Live Transcribe. */
    GPT_LIVE_TRANSCRIBE,

    /** Local capture during record; multipart file upload after Stop. */
    GROK_BATCH,
    ;

    /** True when this strategy opens a realtime session during capture. */
    val usesRealtimeTransport: Boolean
        get() = this != GROK_BATCH

    internal fun realtimeModel(configuredModel: String): String = when (this) {
        OPENAI_REALTIME -> configuredModel
        GPT_LIVE_TRANSCRIBE -> GPT_LIVE_TRANSCRIBE_MODEL
        GROK_BATCH -> configuredModel
    }

    companion object {
        internal const val GPT_LIVE_TRANSCRIBE_MODEL = "gpt-live-transcribe"

        fun fromRaw(raw: String?): VoiceFlowRecordingStrategy =
            when (raw) {
                OPENAI_REALTIME.name, "openAIRealtime", "openai_realtime" -> OPENAI_REALTIME
                GPT_LIVE_TRANSCRIBE.name, "gptLiveTranscribe", "gpt_live_transcribe" ->
                    GPT_LIVE_TRANSCRIBE
                GROK_BATCH.name, "grokBatch", "grok_batch" -> GROK_BATCH
                else -> OPENAI_REALTIME
            }
    }
}
