package com.yage.voiceflowkit.internal

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and writes canonical 16-bit mono PCM WAV files.
 *
 * Port of Swift `PCM16WAVWriter`. Produces the standard 44-byte RIFF/WAVE/fmt/data header
 * (mono, 16-bit, `byteRate = sampleRate * 2`, `blockAlign = 2`), little-endian throughout,
 * then appends the raw PCM payload. [readPcm] is the inverse: it strips the 44-byte header.
 */
internal object Pcm16WavWriter {
    private const val HEADER_SIZE = 44

    /**
     * Writes [pcm] to [out] wrapped in a canonical WAV header.
     *
     * @throws RealtimeTranscriptionError.AudioConversionFailed if [pcm] is empty.
     */
    fun writeWav(pcm: ByteArray, sampleRate: Int = RealtimeTranscriptionConfig.SAMPLE_RATE, out: File) {
        if (pcm.isEmpty()) {
            throw RealtimeTranscriptionError.AudioConversionFailed
        }

        val byteRate = sampleRate * 2 // 16-bit mono.
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)                // ChunkSize
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                           // Subchunk1Size (PCM)
        header.putShort(1)                          // AudioFormat = PCM
        header.putShort(1)                          // NumChannels = mono
        header.putInt(sampleRate)                   // SampleRate
        header.putInt(byteRate)                     // ByteRate
        header.putShort(2)                          // BlockAlign = NumChannels * bytesPerSample
        header.putShort(16)                         // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)                      // Subchunk2Size

        if (out.exists()) {
            out.delete()
        }
        out.outputStream().use { stream ->
            stream.write(header.array())
            stream.write(pcm)
        }
    }

    /**
     * Reads the raw PCM payload from [wav], dropping the 44-byte header.
     *
     * @throws RealtimeTranscriptionError.AudioConversionFailed if the file is missing or has no
     *         payload beyond the header.
     */
    fun readPcm(wav: File): ByteArray {
        if (!wav.exists()) {
            throw RealtimeTranscriptionError.AudioConversionFailed
        }
        val bytes = wav.readBytes()
        if (bytes.size <= HEADER_SIZE) {
            throw RealtimeTranscriptionError.AudioConversionFailed
        }
        return bytes.copyOfRange(HEADER_SIZE, bytes.size)
    }
}
