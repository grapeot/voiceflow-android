package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.RealtimeTranscriptionConfig
import com.yage.voiceflowkit.internal.RealtimeServerStatus
import com.yage.voiceflowkit.internal.RealtimeTranscriptEvent
import com.yage.voiceflowkit.internal.RealtimeWebSocketSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RealtimeWebSocketSessionTest {
    @Test
    fun `GPT Live sends stop after turn completed and preserves frame order`() = runTest {
        val sent = mutableListOf<String>()
        val events = mutableListOf<RealtimeTranscriptEvent>()
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.queueSize() } returns 0L
        every { socket.send(any<ByteString>()) } answers {
            sent += "audio"
            true
        }
        every { socket.send(any<String>()) } answers {
            sent += firstArg<String>()
            true
        }
        val session = RealtimeWebSocketSession(
            webSocket = socket,
            onEvent = { events += it },
            requiresTurnCompletedBeforeStop = true,
        )

        session.sendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))
        session.sendCommit()
        session.onMessage("""{"type":"transcript_completed","text":"done"}""")
        assertFalse(sent.contains(RealtimeTranscriptionConfig.STOP_MESSAGE))
        session.onMessage("""{"type":"turn_completed"}""")

        assertEquals(
            listOf(
                "audio",
                RealtimeTranscriptionConfig.COMMIT_MESSAGE,
                RealtimeTranscriptionConfig.STOP_MESSAGE,
            ),
            sent,
        )
        assertFalse(
            events.contains(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle)),
        )
        session.onMessage("""{"type":"session_stopped"}""")
        assertEquals(
            RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle),
            events.last(),
        )
    }

    @Test
    fun `GPT Live waits for transcript when turn completes first and sends stop once`() = runTest {
        val sent = mutableListOf<String>()
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.queueSize() } returns 0L
        every { socket.send(any<ByteString>()) } returns true
        every { socket.send(any<String>()) } answers {
            sent += firstArg<String>()
            true
        }
        val session = RealtimeWebSocketSession(
            webSocket = socket,
            onEvent = {},
            requiresTurnCompletedBeforeStop = true,
        )

        session.sendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))
        session.sendCommit()
        session.onMessage("""{"type":"turn_completed"}""")
        assertFalse(sent.contains(RealtimeTranscriptionConfig.STOP_MESSAGE))
        session.onMessage("""{"type":"transcript_completed","text":"done"}""")
        session.onMessage("""{"type":"turn_completed"}""")

        assertEquals(1, sent.count { it == RealtimeTranscriptionConfig.STOP_MESSAGE })
    }

    @Test
    fun `GPT Live rejects session stopped before turn completion`() = runTest {
        val events = mutableListOf<RealtimeTranscriptEvent>()
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.queueSize() } returns 0L
        every { socket.send(any<ByteString>()) } returns true
        every { socket.send(any<String>()) } returns true
        val session = RealtimeWebSocketSession(
            webSocket = socket,
            onEvent = { events += it },
            requiresTurnCompletedBeforeStop = true,
        )

        session.sendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))
        session.sendCommit()
        session.onMessage("""{"type":"transcript_completed","text":"partial terminal"}""")
        session.onMessage("""{"type":"session_stopped"}""")

        assertTrue(events.last() is RealtimeTranscriptEvent.ErrorEvent)
        assertFalse(events.contains(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle)))
    }

    @Test
    fun `GPT Live rejects session stopped when stop send fails`() = runTest {
        val events = mutableListOf<RealtimeTranscriptEvent>()
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.queueSize() } returns 0L
        every { socket.send(any<ByteString>()) } returns true
        every { socket.send(any<String>()) } answers {
            firstArg<String>() != RealtimeTranscriptionConfig.STOP_MESSAGE
        }
        val session = RealtimeWebSocketSession(
            webSocket = socket,
            onEvent = { events += it },
            requiresTurnCompletedBeforeStop = true,
        )

        session.sendAudioChunk(ByteArray(RealtimeTranscriptionConfig.minCommitAudioBytes))
        session.sendCommit()
        session.onMessage("""{"type":"turn_completed"}""")
        session.onMessage("""{"type":"transcript_completed","text":"done"}""")
        session.onMessage("""{"type":"session_stopped"}""")

        assertTrue(events.any { it is RealtimeTranscriptEvent.ErrorEvent })
        assertFalse(events.contains(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle)))
    }

    @Test
    fun `audio send applies bounded queue backpressure without dropping the frame`() = runTest {
        var queuedBytes = 2_000_000L
        var sendCount = 0
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.queueSize() } answers { queuedBytes }
        every { socket.send(any<ByteString>()) } answers {
            sendCount += 1
            true
        }
        val session = RealtimeWebSocketSession(webSocket = socket, onEvent = {})

        val sendJob = launch { session.sendAudioChunk(ByteArray(24_000)) }
        runCurrent()
        assertEquals(0, sendCount)

        queuedBytes = 0L
        advanceTimeBy(5)
        sendJob.join()
        assertEquals(1, sendCount)
    }
}
