package com.yage.voiceflowkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Signal quality detection tests. The tier evaluation logic lives in the
 * app module (MainViewModel + SignalTier/SignalQualityConfig), but the RMS
 * helper lives in the kit. These tests validate the kit-side RMS helper
 * and mirror the tier evaluation with local constants so the full signal
 * gate can be tested in one place without a cross-module dependency.
 */
class SignalQualityTest {

    // Mirror of app's SignalQualityConfig constants for tier tests.
    private val speechThreshold = 0.008f
    private val activeAudioTier1Ms = 100.0
    private val activeAudioShortMs = 1500.0

    // Mirror of app's SignalTier enum for tier tests.
    private enum class SignalTier { Tier1NoSignal, Tier2ShortAudio, Tier3Normal }

    // Mirror of app's evaluateSignalTier for tier tests.
    private fun evaluateSignalTier(activeAudioMs: Double): SignalTier {
        if (activeAudioMs < activeAudioTier1Ms) return SignalTier.Tier1NoSignal
        if (activeAudioMs < activeAudioShortMs) return SignalTier.Tier2ShortAudio
        return SignalTier.Tier3Normal
    }

    // --- RMS helper ---

    @Test
    fun rmsLevelOfSilenceIsNearZero() {
        val silence = ByteArray(8192) // all zeros
        val rms = VoiceFlowAudioMetering.rmsLevel(silence)
        assertEquals(0f, rms, 0.0001f)
    }

    @Test
    fun rmsLevelOfLoudToneExceedsSpeechThreshold() {
        val samples = ShortArray(4096) { i ->
            (10000.0 * sin(i * 0.1)).toInt().toShort()
        }
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            data[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            data[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        val rms = VoiceFlowAudioMetering.rmsLevel(data)
        assertTrue(rms > speechThreshold)
    }

    @Test
    fun rmsLevelOfQuietToneBelowSpeechThreshold() {
        val samples = ShortArray(4096) { i ->
            (200.0 * sin(i * 0.1)).toInt().toShort()
        }
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            data[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            data[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        val rms = VoiceFlowAudioMetering.rmsLevel(data)
        // 200/32768 ≈ 0.006 RMS for a sine — below speechThreshold(0.008)
        assertTrue(rms < speechThreshold)
    }

    // --- Tier evaluation ---

    @Test
    fun tier1WhenNoSpeechDetected() {
        assertEquals(SignalTier.Tier1NoSignal, evaluateSignalTier(0.0))
    }

    @Test
    fun tier1WithSmallNoiseButNoSpeech() {
        assertEquals(SignalTier.Tier1NoSignal, evaluateSignalTier(50.0))
    }

    @Test
    fun tier2WhenActiveAudioShortButPresent() {
        assertEquals(SignalTier.Tier2ShortAudio, evaluateSignalTier(500.0))
    }

    @Test
    fun tier3WhenActiveAudioExceedsCutoff() {
        assertEquals(SignalTier.Tier3Normal, evaluateSignalTier(2000.0))
    }

    @Test
    fun tier2BoundaryExactlyAtCutoff() {
        assertEquals(SignalTier.Tier3Normal, evaluateSignalTier(activeAudioShortMs))
    }
}