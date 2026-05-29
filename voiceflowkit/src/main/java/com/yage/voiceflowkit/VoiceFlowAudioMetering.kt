package com.yage.voiceflowkit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Public helper for hosts who want to compute the same audio level
 * VoiceFlowKit uses internally — e.g. when feeding the microphone from
 * an existing capture pipeline instead of [VoiceFlowMicrophone].
 *
 * Direct port of the Swift `VoiceFlowAudioMetering` enum. Computes a
 * 0..1 level from a PCM16 little-endian chunk using
 * RMS → dB → linear remap → 0.9× tail. This matches what
 * [VoiceFlowMicrophone.audioLevel] publishes, minus the EMA smoothing
 * (the caller can apply that themselves if needed).
 *
 * The Swift source assembles each sample as `(hi << 8) | (lo & 0xFF)`,
 * which is a correct little-endian read; here we use a
 * [ByteBuffer] in [ByteOrder.LITTLE_ENDIAN] to read each `Short`, which
 * is equivalent and faster.
 */
object VoiceFlowAudioMetering {

    private const val MIN_DB = -50.0
    private const val MAX_DB = -10.0
    private const val TAIL_SCALE = 0.9

    /**
     * Compute a 0..1 level from a PCM16 little-endian chunk.
     *
     * Returns `0f` for an empty chunk (or one with fewer than 2 bytes).
     * A trailing odd byte, if present, is ignored — only whole samples
     * contribute to the RMS.
     */
    fun normalizedLevel(pcm16le: ByteArray): Float {
        val sampleCount = pcm16le.size / 2
        if (sampleCount <= 0) return 0f

        val buffer = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        var accumulator = 0.0
        for (i in 0 until sampleCount) {
            val sample = buffer.short.toDouble() / 32768.0
            accumulator += sample * sample
        }

        val rms = sqrt(accumulator / sampleCount)
        val db = 20.0 * log10(max(rms, 1e-7))
        val normalized = (db - MIN_DB) / (MAX_DB - MIN_DB)
        val scaled = normalized * TAIL_SCALE
        return min(max(scaled, 0.0), 1.0).toFloat()
    }
}
