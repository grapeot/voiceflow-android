package com.yage.voiceflow.model

/**
 * Signal quality tier evaluated at Stop time. Direct port of the iOS
 * `AppState.SignalTier` enum.
 *
 * - [Tier1NoSignal]: no speech detected at all (activeAudioMs < 100).
 *   Don't commit — OpenAI would hallucinate on empty audio.
 * - [Tier2ShortAudio]: some speech detected but too short (activeAudioMs
 *   < 1500ms). Commit normally, show a warning above the transcript.
 * - [Tier3Normal]: enough speech detected (activeAudioMs >= 1500ms).
 *   No warning, normal flow.
 */
enum class SignalTier {
    Tier1NoSignal,
    Tier2ShortAudio,
    Tier3Normal,
}

/**
 * Signal quality detection thresholds. Tuned to match the iOS app.
 */
object SignalQualityConfig {
    const val SPEECH_THRESHOLD = 0.015f
    const val ACTIVE_AUDIO_TIER1_MS = 300.0
    const val ACTIVE_AUDIO_SHORT_MS = 1500.0
    const val SIGNAL_BANNER_GRACE_MS = 300L
}