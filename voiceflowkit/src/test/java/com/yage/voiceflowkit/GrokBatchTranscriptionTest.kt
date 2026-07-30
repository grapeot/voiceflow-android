package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.GrokBatchTranscriptionClient
import com.yage.voiceflowkit.internal.MockGrokBatchTranscriptionClient
import com.yage.voiceflowkit.internal.MockRealtimeTranscriptionClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GrokBatchTranscriptionTest {

    @Test
    fun `strategy flags match iOS transport split`() {
        assertTrue(VoiceFlowRecordingStrategy.OPENAI_REALTIME.usesRealtimeTransport)
        assertFalse(VoiceFlowRecordingStrategy.GROK_BATCH.usesRealtimeTransport)
    }

    @Test
    fun `strategy routes to Grok without realtime bulk`() = runTest {
        val grok = MockGrokBatchTranscriptionClient(
            Result.success(TranscriptionResult(text = "grok text", requestId = "req-1")),
        )
        val realtime = MockRealtimeTranscriptionClient(
            liveTranscript = "live",
            bulkTranscript = "bulk should not run",
        )
        val client = VoiceFlowClient.forTests(
            config = VoiceFlowConfig(tokenProvider = { "token" }),
            transcriber = realtime,
            grokTranscriber = grok,
        )
        val file = File.createTempFile("grok", ".wav").apply {
            writeBytes(ByteArray(100) { 1 })
            deleteOnExit()
        }

        val result = client.transcribe(
            audioFile = file,
            strategy = VoiceFlowRecordingStrategy.GROK_BATCH,
        )

        assertEquals("grok text", result.text)
        assertEquals(1, grok.calls.size)
        assertEquals(file, grok.calls.single().first)
    }

    @Test
    fun `multipart request preserves mount filename and terms`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"request_id":"abc","text":"hello world"}"""),
        )
        server.start()
        try {
            val client = GrokBatchTranscriptionClient()
            val file = File.createTempFile("sample", ".wav").apply {
                writeBytes(ByteArray(64) { 2 })
                deleteOnExit()
            }
            val result = client.transcribe(
                audioFile = file,
                baseURL = server.url("/backend").toString().trimEnd('/'),
                token = "tok",
                terms = listOf(" VoiceFlow ", "", "Grok"),
            )
            assertEquals("hello world", result.text)
            assertEquals("abc", result.requestId)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertTrue(recorded.path!!.endsWith("/v1/audio/grok-transcription"))
            assertEquals("Bearer tok", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("name=\"audio_file\""))
            assertTrue(body.contains("filename=\"${file.name}\""))
            assertTrue(body.contains("name=\"terms\""))
            assertTrue(body.contains("VoiceFlow,Grok"))
            assertFalse(body.contains("name=\"prompt\""))
            assertFalse(body.contains("name=\"simple\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `connection test uses usage summary endpoint`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        try {
            val client = VoiceFlowClient(
                VoiceFlowConfig(
                    endpoint = server.url("/backend").toString().trimEnd('/'),
                    tokenProvider = { "tok" },
                ),
            )
            client.testConnection()
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertTrue(recorded.path!!.endsWith("/v1/usage/summary"))
            assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
