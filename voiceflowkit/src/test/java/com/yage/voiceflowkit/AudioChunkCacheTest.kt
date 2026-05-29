package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.AudioChunkCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the disk-backed [AudioChunkCache] used for WebSocket recovery replay:
 * append/byteCount bookkeeping, windowed reads, the empty array past the live tail,
 * and file removal.
 */
class AudioChunkCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `append accumulates byte count and ignores empty input`() {
        val cache = AudioChunkCache(tempFolder.newFolder("cache1"))
        assertEquals(0, cache.byteCount)
        cache.append(byteArrayOf(1, 2, 3))
        cache.append(ByteArray(0))
        cache.append(byteArrayOf(4, 5))
        assertEquals(5, cache.byteCount)
    }

    @Test
    fun `readChunk returns a window at the offset`() {
        val cache = AudioChunkCache(tempFolder.newFolder("cache2"))
        cache.append(byteArrayOf(10, 11, 12, 13, 14, 15))
        assertArrayEquals(byteArrayOf(10, 11, 12), cache.readChunk(offset = 0, maxBytes = 3))
        assertArrayEquals(byteArrayOf(13, 14, 15), cache.readChunk(offset = 3, maxBytes = 3))
    }

    @Test
    fun `readChunk clamps the window to the live tail`() {
        val cache = AudioChunkCache(tempFolder.newFolder("cache3"))
        cache.append(byteArrayOf(1, 2, 3, 4))
        // Asking for more than available returns only what exists.
        assertArrayEquals(byteArrayOf(3, 4), cache.readChunk(offset = 2, maxBytes = 100))
    }

    @Test
    fun `readChunk past the tail returns an empty array`() {
        val cache = AudioChunkCache(tempFolder.newFolder("cache4"))
        cache.append(byteArrayOf(1, 2))
        assertEquals(0, cache.readChunk(offset = 2, maxBytes = 8).size)
        assertEquals(0, cache.readChunk(offset = 99, maxBytes = 8).size)
    }

    @Test
    fun `remove deletes the file and resets the counter`() {
        val cache = AudioChunkCache(tempFolder.newFolder("cache5"))
        cache.append(byteArrayOf(1, 2, 3))
        assertTrue(cache.file.exists())
        cache.remove()
        assertFalse(cache.file.exists())
        assertEquals(0, cache.byteCount)
    }
}
