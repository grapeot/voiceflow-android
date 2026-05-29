package com.yage.voiceflowkit

/**
 * Result of a one-shot transcription (see [VoiceFlowClient.transcribe]).
 *
 * Port of the Swift `TranscriptionResult` struct. [requestId] is a fresh
 * UUID minted per request (the Swift source uses `UUID().uuidString`).
 */
data class TranscriptionResult(
    val text: String,
    val requestId: String,
)
