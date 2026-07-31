package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.BulkTranscriptionProgress
import com.yage.voiceflowkit.internal.MockGrokBatchTranscriptionClient
import com.yage.voiceflowkit.internal.MockRealtimeTranscriptionClient
import com.yage.voiceflowkit.internal.OrderedRealtimeEventDispatcher
import com.yage.voiceflowkit.internal.RealtimeServerStatus
import com.yage.voiceflowkit.internal.RealtimeSessionContext
import com.yage.voiceflowkit.internal.RealtimeTranscriptEvent
import com.yage.voiceflowkit.internal.RealtimeTranscriptionConfig
import com.yage.voiceflowkit.internal.RealtimeTranscriptionError
import com.yage.voiceflowkit.internal.realtimeSessionCreatePayload
import com.yage.voiceflowkit.internal.resolveCompletedBulkTranscript
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
class GptLiveTranscribeTest {
    @Test
    fun `strategy parsing and capabilities preserve all raw values`() {
        assertEquals(3, VoiceFlowRecordingStrategy.entries.size)
        VoiceFlowRecordingStrategy.entries.forEach { strategy ->
            assertSame(strategy, VoiceFlowRecordingStrategy.fromRaw(strategy.name))
        }
        assertEquals(
            VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
            VoiceFlowRecordingStrategy.fromRaw("gptLiveTranscribe"),
        )
        assertEquals(
            VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            VoiceFlowRecordingStrategy.fromRaw("unknown-future-value"),
        )
        assertTrue(VoiceFlowRecordingStrategy.OPENAI_REALTIME.usesRealtimeTransport)
        assertTrue(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE.usesRealtimeTransport)
        assertFalse(VoiceFlowRecordingStrategy.GROK_BATCH.usesRealtimeTransport)
    }

    @Test
    fun `GPT Live timeout scales with PCM duration while GPT Realtime stays fixed`() {
        val oneMinute = 60 * RealtimeTranscriptionConfig.PCM_BYTES_PER_SECOND.toInt()
        val fiveMinutes = 300 * RealtimeTranscriptionConfig.PCM_BYTES_PER_SECOND.toInt()

        assertEquals(
            120_000L,
            RealtimeTranscriptionConfig.finalizeTimeoutMs(
                VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                oneMinute,
            ),
        )
        assertEquals(
            360_000L,
            RealtimeTranscriptionConfig.finalizeTimeoutMs(
                VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE,
                fiveMinutes,
            ),
        )
        assertEquals(
            30_000L,
            RealtimeTranscriptionConfig.finalizeTimeoutMs(
                VoiceFlowRecordingStrategy.OPENAI_REALTIME,
                fiveMinutes,
            ),
        )
    }

    @Test
    fun `strategy aware start preserves custom realtime model and pins GPT Live model`() = runTest {
        val realtime = MockRealtimeTranscriptionClient("live", "bulk")
        val client = testClient(realtime, model = "custom-realtime-model")

        val realtimeSession = client.startSession()
        assertEquals(VoiceFlowRecordingStrategy.OPENAI_REALTIME, realtimeSession.strategy)
        assertEquals("custom-realtime-model", realtime.lastLiveModel())

        val liveSession = client.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE)
        assertEquals(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE, liveSession.strategy)
        assertEquals("gpt-live-transcribe", realtime.lastLiveModel())
        assertEquals(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE, realtime.lastLiveStrategy())
    }

    @Test
    fun `Grok cannot be started as a realtime session`() = runTest {
        val client = testClient(MockRealtimeTranscriptionClient("live", "bulk"), "custom")

        try {
            client.startSession(VoiceFlowRecordingStrategy.GROK_BATCH)
            throw AssertionError("expected unsupported strategy")
        } catch (error: VoiceFlowError.UnsupportedStrategy) {
            assertEquals(VoiceFlowRecordingStrategy.GROK_BATCH, error.strategy)
        }
    }

    @Test
    fun `preserved retry keeps originating GPT Live strategy after config changes`() = runTest {
        val realtime = MockRealtimeTranscriptionClient("live", "retried")
        val client = testClient(realtime, model = "first-custom-model")
        val session = client.startSession(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE)
        session.sendAudioChunk(ByteArray(4_800) { 1 })
        val preserved = session.abortPreservingAudio()!!

        client.updateConfig(
            VoiceFlowConfig(model = "second-custom-model", tokenProvider = { "token" }),
        )
        val result = client.transcribe(preserved)

        assertEquals("retried", result.text)
        assertEquals(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE, preserved.strategy)
        assertEquals(VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE, realtime.lastBulkStrategy())
        assertEquals("gpt-live-transcribe", realtime.lastBulkModel())
        client.discardPreservedAudio(preserved)
    }

    @Test
    fun `preserved retry keeps originating resolved custom realtime model`() = runTest {
        val realtime = MockRealtimeTranscriptionClient("live", "retried")
        val client = testClient(realtime, model = "first-custom-model")
        val session = client.startSession(VoiceFlowRecordingStrategy.OPENAI_REALTIME)
        session.sendAudioChunk(ByteArray(4_800) { 1 })
        val preserved = session.abortPreservingAudio()!!

        client.updateConfig(
            VoiceFlowConfig(model = "second-custom-model", tokenProvider = { "token" }),
        )
        client.transcribe(preserved)

        assertEquals("first-custom-model", realtime.lastBulkModel())
        client.discardPreservedAudio(preserved)
    }

    @Test
    fun `GPT Live session body keeps normalized contract only`() {
        val payload = realtimeSessionCreatePayload(
            model = "gpt-live-transcribe",
            vad = false,
            context = RealtimeSessionContext(" preserve punctuation ", listOf("VoiceFlow", "gRPC")),
        )

        assertEquals("gpt-live-transcribe", payload.getString("model"))
        assertFalse(payload.getBoolean("vad"))
        assertEquals("preserve punctuation", payload.getString("prompt"))
        assertEquals(2, payload.getJSONArray("terms").length())
        assertFalse(payload.has("engine"))
        assertFalse(payload.has("intent"))
    }

    @Test
    fun `partial transcript at deadline is not a completed bulk result`() = runTest {
        val progress = BulkTranscriptionProgress()
        progress.handle(RealtimeTranscriptEvent.TextDelta("partial", false), null)

        try {
            resolveCompletedBulkTranscript(progress)
            throw AssertionError("expected timeout")
        } catch (error: RealtimeTranscriptionError.ConnectionLost) {
            assertTrue(error.detail.contains("Timed out"))
        }
    }

    @Test
    fun `inbound event dispatcher preserves callback order`() = runTest {
        val observed = mutableListOf<RealtimeTranscriptEvent>()
        val dispatcher = OrderedRealtimeEventDispatcher(this) { event ->
            if (event is RealtimeTranscriptEvent.TextDelta) delay(10)
            observed += event
        }
        val first = RealtimeTranscriptEvent.TextDelta("first", false)
        val second = RealtimeTranscriptEvent.TextDelta("second", false)
        val terminal = RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle)

        dispatcher.dispatch(first)
        dispatcher.dispatch(second)
        dispatcher.dispatch(terminal)
        dispatcher.finishAndJoin()

        assertEquals(listOf(first, second, terminal), observed)
    }

    private fun testClient(
        realtime: MockRealtimeTranscriptionClient,
        model: String,
    ): VoiceFlowClient = VoiceFlowClient.forTests(
        config = VoiceFlowConfig(model = model, tokenProvider = { "token" }),
        transcriber = realtime,
        grokTranscriber = MockGrokBatchTranscriptionClient(
            Result.success(TranscriptionResult("grok", "request")),
        ),
    )
}
