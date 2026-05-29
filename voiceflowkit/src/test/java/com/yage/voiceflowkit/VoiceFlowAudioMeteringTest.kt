package com.yage.voiceflowkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Verifies [VoiceFlowAudioMetering.normalizedLevel] matches the Swift
 * RMS → dB[-50..-10] → linear × 0.9 formula and its clamping behavior.
 */
class VoiceFlowAudioMeteringTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    @Test
    fun `empty chunk yields zero`() {
        assertEquals(0f, VoiceFlowAudioMetering.normalizedLevel(ByteArray(0)), 0f)
    }

    @Test
    fun `single trailing byte yields zero`() {
        assertEquals(0f, VoiceFlowAudioMetering.normalizedLevel(byteArrayOf(0x7F)), 0f)
    }

    @Test
    fun `silence clamps to zero`() {
        // Pure silence -> rms 0 -> dB floored to -140 -> normalized far below 0 -> clamped.
        assertEquals(0f, VoiceFlowAudioMetering.normalizedLevel(pcm(0, 0, 0, 0)), 0f)
    }

    @Test
    fun `full scale clamps to one`() {
        // Max amplitude -> dB ~0 -> well above max -> clamped to 1.
        val level = VoiceFlowAudioMetering.normalizedLevel(
            pcm(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()),
        )
        assertEquals(1f, level, 0f)
    }

    @Test
    fun `mid level matches formula`() {
        val samples = intArrayOf(2000, -2000, 2000, -2000)
        val bytes = pcm(*samples)

        // Recompute the reference value exactly as the implementation should.
        val rms = sqrt(samples.map { (it / 32768.0).let { s -> s * s } }.sum() / samples.size)
        val db = 20.0 * log10(maxOf(rms, 1e-7))
        val expected = ((db - (-50.0)) / ((-10.0) - (-50.0)) * 0.9)
            .coerceIn(0.0, 1.0)
            .toFloat()

        assertEquals(expected, VoiceFlowAudioMetering.normalizedLevel(bytes), 1e-5f)
        assertTrue(expected in 0f..1f)
    }
}
