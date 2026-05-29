package com.yage.voiceflowkit.internal

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Disk-backed, thread-safe PCM ring of bytes for the live transcription path.
 *
 * Port of Swift `AudioChunkCache` (Internal/AudioChunkEncoder.swift) reconciled
 * with the proven Android reference `RealtimeSpeechAudioCache`
 * (opencode_android_client). Every audio chunk that has been captured is appended
 * here before it is sent. When the WebSocket drops and a new one is opened, the
 * recovery path re-reads the cache from byte 0 so the server receives the full
 * audio stream again — the backend treats each socket as a fresh session and we
 * must replay everything captured so far.
 *
 * The class is intentionally constructed with a plain [File] directory (not a
 * `Context`) so it can be unit-tested without Android framework stubs.
 */
internal class AudioChunkCache(
    directory: File,
) {
    /** Backing file. One `.pcm` file per live session, named with a fresh UUID. */
    val file: File = File(directory, "voiceflow-stream-${UUID.randomUUID()}.pcm")

    private val lock = Any()
    private var byteCountValue = 0

    init {
        directory.mkdirs()
        file.createNewFile()
    }

    /** Total number of bytes appended so far (live tail length). */
    val byteCount: Int
        get() = synchronized(lock) { byteCountValue }

    /** Append a captured PCM chunk to the tail of the cache. No-op for empty input. */
    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            FileOutputStream(file, true).use { output ->
                output.write(data)
            }
            byteCountValue += data.size
        }
    }

    /**
     * Read up to [maxBytes] starting at [offset]. Returns an empty array once
     * [offset] reaches the live tail (so the replay loop can detect "caught up").
     */
    fun readChunk(offset: Int, maxBytes: Int): ByteArray {
        require(offset >= 0) { "offset must be non-negative" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        synchronized(lock) {
            if (offset >= byteCountValue) return ByteArray(0)
            val readSize = minOf(maxBytes, byteCountValue - offset)
            val buffer = ByteArray(readSize)
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset.toLong())
                input.readFully(buffer)
            }
            return buffer
        }
    }

    /** Delete the backing file and reset the byte counter. */
    fun remove() {
        synchronized(lock) {
            file.delete()
            byteCountValue = 0
        }
    }
}
