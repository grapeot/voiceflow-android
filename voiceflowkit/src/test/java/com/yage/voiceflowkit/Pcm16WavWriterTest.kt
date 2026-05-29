package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.Pcm16WavWriter
import com.yage.voiceflowkit.internal.RealtimeTranscriptionConfig
import com.yage.voiceflowkit.internal.RealtimeTranscriptionError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies [Pcm16WavWriter] writes a canonical 44-byte RIFF/WAVE/fmt/data header for
 * 24kHz mono PCM16 and round-trips the payload via [Pcm16WavWriter.readPcm].
 */
class Pcm16WavWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun samplePcm(): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        intArrayOf(1, -2, 32767, -32768).forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    @Test
    fun `write then read round-trips the pcm payload`() {
        val pcm = samplePcm()
        val out = tempFolder.newFile("roundtrip.wav")
        Pcm16WavWriter.writeWav(pcm, out = out)
        assertArrayEquals(pcm, Pcm16WavWriter.readPcm(out))
    }

    @Test
    fun `header is a canonical 44-byte WAV header for 24kHz mono pcm16`() {
        val pcm = samplePcm()
        val out = tempFolder.newFile("header.wav")
        Pcm16WavWriter.writeWav(pcm, sampleRate = RealtimeTranscriptionConfig.SAMPLE_RATE, out = out)

        val bytes = out.readBytes()
        assertEquals(44 + pcm.size, bytes.size)

        // ASCII chunk markers.
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(bytes.copyOfRange(36, 40), Charsets.US_ASCII))

        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, header.getInt(4))         // ChunkSize
        assertEquals(16, header.getInt(16))                   // Subchunk1Size
        assertEquals(1.toShort(), header.getShort(20))        // AudioFormat = PCM
        assertEquals(1.toShort(), header.getShort(22))        // NumChannels = mono
        assertEquals(24_000, header.getInt(24))               // SampleRate
        assertEquals(24_000 * 2, header.getInt(28))           // ByteRate = sampleRate * 2
        assertEquals(2.toShort(), header.getShort(32))        // BlockAlign
        assertEquals(16.toShort(), header.getShort(34))       // BitsPerSample
        assertEquals(pcm.size, header.getInt(40))             // Subchunk2Size
    }

    @Test
    fun `writeWav rejects empty pcm`() {
        val out = tempFolder.newFile("empty.wav")
        assertThrows(RealtimeTranscriptionError.AudioConversionFailed::class.java) {
            Pcm16WavWriter.writeWav(ByteArray(0), out = out)
        }
    }

    @Test
    fun `readPcm throws when the file is header-only`() {
        val out = tempFolder.newFile("headeronly.wav")
        out.writeBytes(ByteArray(44))
        assertThrows(RealtimeTranscriptionError.AudioConversionFailed::class.java) {
            Pcm16WavWriter.readPcm(out)
        }
    }

    @Test
    fun `readPcm throws when the file is missing`() {
        val missing = tempFolder.root.resolve("does-not-exist.wav")
        assertThrows(RealtimeTranscriptionError.AudioConversionFailed::class.java) {
            Pcm16WavWriter.readPcm(missing)
        }
    }
}
