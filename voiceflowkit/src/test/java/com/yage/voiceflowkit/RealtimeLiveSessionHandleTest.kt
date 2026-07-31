package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.AudioChunkCache
import com.yage.voiceflowkit.internal.RealtimeLiveSessionHandle
import com.yage.voiceflowkit.internal.RealtimeTranscriptEvent
import com.yage.voiceflowkit.internal.RealtimeTranscriptionConfig
import com.yage.voiceflowkit.internal.RealtimeTranscriptionError
import com.yage.voiceflowkit.internal.RealtimeWebSocketSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RealtimeLiveSessionHandleTest {
    @Test
    fun `recovery replay and socket adoption block concurrent cache append`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-recovery-test").toFile()
        val cache = AudioChunkCache(directory)
        val initialSocket = socket(sendAudio = { false })
        val replayStarted = CountDownLatch(1)
        val releaseReplay = CountDownLatch(1)
        val replacementFrames = mutableListOf<Int>()
        val replacementSocket = socket { bytes ->
            synchronized(replacementFrames) { replacementFrames += bytes.size }
            if (replacementFrames.size == 1) {
                replayStarted.countDown()
                assertTrue(releaseReplay.await(5, TimeUnit.SECONDS))
            }
            true
        }
        val replacement = RealtimeWebSocketSession(replacementSocket, onEvent = {})
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = { replacement },
            strategy = VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            model = "custom-model",
        )
        handle.attachInitialSession(RealtimeWebSocketSession(initialSocket, onEvent = {}))

        val first = launch(Dispatchers.Default) {
            handle.appendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes) { 1 })
        }
        assertTrue(replayStarted.await(5, TimeUnit.SECONDS))
        val second = launch(Dispatchers.Default) {
            handle.appendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes) { 2 })
        }

        Thread.sleep(50)
        assertEquals(RealtimeTranscriptionConfig.minCommitAudioBytes, cache.byteCount)
        assertFalse(second.isCompleted)
        releaseReplay.countDown()
        first.join()
        second.join()

        assertEquals(RealtimeTranscriptionConfig.minCommitAudioBytes * 2, cache.byteCount)
        assertEquals(cache.byteCount, replacement.pendingCommitAudioBytes)
        assertEquals(
            listOf(
                RealtimeTranscriptionConfig.minCommitAudioBytes,
                RealtimeTranscriptionConfig.minCommitAudioBytes,
            ),
            synchronized(replacementFrames) { replacementFrames.toList() },
        )
        handle.cancel()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `raw transcript events are never forwarded to UI`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-event-test").toFile()
        val cache = AudioChunkCache(directory)
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = { RealtimeWebSocketSession(socket { true }, onEvent = {}) },
            strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            model = "gpt-live-transcribe",
        )

        assertFalse(
            handle.ingestServerEvent(
                RealtimeLiveSessionHandle.INITIAL_GENERATION,
                RealtimeTranscriptEvent.TextDelta("raw", false),
            ),
        )
        handle.cancel()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `GPT Live finalize does not automatically retry a failed commit`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-finalize-test").toFile()
        val cache = AudioChunkCache(directory)
        var recoverySessionCount = 0
        val active = RealtimeWebSocketSession(socket { true }, onEvent = {})
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = {
                recoverySessionCount += 1
                RealtimeWebSocketSession(socket { true }, onEvent = {})
            },
            strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            model = "gpt-live-transcribe",
        )
        handle.attachInitialSession(active)
        handle.appendAudioChunk(byteArrayOf(1))

        try {
            handle.finalize(null)
            throw AssertionError("expected commit failure")
        } catch (_: RealtimeTranscriptionError.WebsocketError) {
            // The sub-minimum commit fails on the current socket.
        }

        assertEquals(0, recoverySessionCount)
        val preserved = handle.abortPreservingAudio()!!
        assertEquals("gpt-live-transcribe", preserved.model)
        assertEquals(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE, preserved.strategy)
        preserved.file.delete()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `finalize recovers unless socket byte count exactly matches cache`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-sync-test").toFile()
        val cache = AudioChunkCache(directory)
        val initial = RealtimeWebSocketSession(socket { true }, onEvent = {})
        val replacementSocket = mockk<WebSocket>(relaxed = true)
        every { replacementSocket.queueSize() } returns 0L
        every { replacementSocket.send(any<ByteString>()) } returns true
        every { replacementSocket.send(any<String>()) } returns false
        val replacement = RealtimeWebSocketSession(replacementSocket, onEvent = {})
        var recoverySessionCount = 0
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = {
                recoverySessionCount += 1
                replacement
            },
            strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            model = "gpt-live-transcribe",
        )
        handle.attachInitialSession(initial)
        handle.appendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))
        cache.append(byteArrayOf(1, 2, 3))

        try {
            handle.finalize(null)
            throw AssertionError("expected commit failure")
        } catch (_: RealtimeTranscriptionError.WebsocketError) {
            // The replacement intentionally rejects commit after replay.
        }

        assertEquals(1, recoverySessionCount)
        assertEquals(cache.byteCount, replacement.pendingCommitAudioBytes)
        handle.cancel()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `quick finalize waits for pending initial connection without recovery`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-initial-wait-test").toFile()
        val cache = AudioChunkCache(directory)
        var recoverySessionCount = 0
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = {
                recoverySessionCount += 1
                RealtimeWebSocketSession(socket { true }, onEvent = {})
            },
            strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            model = "gpt-live-transcribe",
        )
        handle.appendAudioChunk(byteArrayOf(1))

        val finalize = async { runCatching { handle.finalize(null) } }
        yield()
        assertFalse(finalize.isCompleted)
        assertEquals(0, recoverySessionCount)

        handle.attachInitialSession(RealtimeWebSocketSession(socket { true }, onEvent = {}))
        assertTrue(finalize.await().isFailure)
        assertEquals(0, recoverySessionCount)
        handle.cancel()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `events from replaced socket cannot mutate or finish current generation`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-generation-test").toFile()
        val cache = AudioChunkCache(directory)
        val replacement = RealtimeWebSocketSession(socket { true }, onEvent = {})
        var replacementCount = 0
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = { generation ->
                replacementCount += 1
                assertEquals(2L, generation)
                replacement
            },
            strategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            model = "gpt-live-transcribe",
        )
        handle.attachInitialSession(RealtimeWebSocketSession(socket { false }, onEvent = {}))
        handle.appendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))

        val finalize = async { handle.finalize(null) }
        yield()
        assertFalse(
            handle.ingestServerEvent(
                RealtimeLiveSessionHandle.INITIAL_GENERATION,
                RealtimeTranscriptEvent.TextDelta("stale", isNewResponse = true),
            ),
        )
        assertFalse(
            handle.ingestServerEvent(
                RealtimeLiveSessionHandle.INITIAL_GENERATION,
                RealtimeTranscriptEvent.Status(com.yage.voiceflowkit.internal.RealtimeServerStatus.Idle),
            ),
        )
        assertFalse(
            handle.ingestServerEvent(
                RealtimeLiveSessionHandle.INITIAL_GENERATION,
                RealtimeTranscriptEvent.Disconnected,
            ),
        )
        assertFalse(finalize.isCompleted)
        assertEquals(1, replacementCount)

        handle.ingestServerEvent(2L, RealtimeTranscriptEvent.TextDelta("current", true))
        handle.ingestServerEvent(
            2L,
            RealtimeTranscriptEvent.Status(com.yage.voiceflowkit.internal.RealtimeServerStatus.Idle),
        )
        assertEquals("current", finalize.await())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `abort ownership prevents concurrent cancel from deleting returned audio`() = runBlocking {
        val directory = Files.createTempDirectory("voiceflow-abort-race-test").toFile()
        val cache = AudioChunkCache(directory)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val socket = socket(sendAudio = { true }) {
            closeStarted.countDown()
            assertTrue(releaseClose.await(5, TimeUnit.SECONDS))
        }
        val handle = RealtimeLiveSessionHandle(
            cache = cache,
            onEvent = {},
            makeSession = { RealtimeWebSocketSession(socket { true }, onEvent = {}) },
            strategy = VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            model = "custom-model",
        )
        handle.attachInitialSession(RealtimeWebSocketSession(socket, onEvent = {}))
        handle.appendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))

        val returned = AtomicReference<VoiceFlowPreservedAudio?>()
        val abort = launch(Dispatchers.Default) {
            returned.set(handle.abortPreservingAudio())
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        val cancelFinished = CountDownLatch(1)
        launch(Dispatchers.Default) {
            handle.cancel()
            cancelFinished.countDown()
        }
        assertTrue(cancelFinished.await(5, TimeUnit.SECONDS))
        releaseClose.countDown()
        abort.join()

        val preserved = returned.get()!!
        assertTrue(preserved.file.exists())
        assertEquals(preserved.id, handle.abortPreservingAudio()!!.id)
        preserved.file.delete()
        directory.deleteRecursively()
        Unit
    }

    private fun socket(sendAudio: (ByteString) -> Boolean): WebSocket =
        socket(sendAudio, onCancel = {})

    private fun socket(
        sendAudio: (ByteString) -> Boolean,
        onCancel: () -> Unit,
    ): WebSocket {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.queueSize() } returns 0L
        every { socket.send(any<ByteString>()) } answers { sendAudio(firstArg()) }
        every { socket.send(any<String>()) } returns true
        every { socket.cancel() } answers { onCancel() }
        return socket
    }
}
