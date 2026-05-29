package com.yage.voiceflowkit

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public-facade smoke test mirroring the Swift `PublicFacadeSmokeTests`. Drives the
 * offline [VoiceFlowClient.makeStub] through the full live-session round trip
 * (startSession -> sendAudioChunk -> commitAndStop) and the one-shot transcribe path,
 * and asserts the reactive [VoiceFlowSession.events] flow emits the bridged events.
 */
class VoiceFlowClientStubTest {

    @Test
    fun `module exposes a non-empty version`() {
        assertTrue(VoiceFlowKit.VERSION.isNotEmpty())
    }

    @Test
    fun `config carries the documented defaults`() = runTest {
        val config = VoiceFlowConfig(tokenProvider = { "fake" })
        assertEquals(VoiceFlowConfig.DEFAULT_ENDPOINT, config.endpoint)
        assertEquals(VoiceFlowConfig.DEFAULT_MODEL, config.model)
        assertEquals(null, config.prompt)
        assertTrue(config.terms.isEmpty())
    }

    @Test
    fun `currentConfig returns the latest after updateConfig`() = runTest {
        val client = VoiceFlowClient.makeStub()
        val updated = VoiceFlowConfig(
            endpoint = "https://example.com/api",
            tokenProvider = { "t" },
            prompt = "hint",
            terms = listOf("VoiceFlow"),
        )
        client.updateConfig(updated)
        val current = client.currentConfig()
        assertEquals("https://example.com/api", current.endpoint)
        assertEquals("hint", current.prompt)
        assertEquals(listOf("VoiceFlow"), current.terms)
    }

    @Test
    fun `stub session commitAndStop returns the canned transcript`() = runTest {
        val client = VoiceFlowClient.makeStub(liveTranscript = "Hello from the stub")
        val session = client.startSession()
        session.sendAudioChunk(ByteArray(4800) { 1 })
        val transcript = session.commitAndStop()
        assertEquals("Hello from the stub", transcript)
    }

    @Test
    fun `stub session commitAndStop fires the partial callback`() = runTest {
        val client = VoiceFlowClient.makeStub(liveTranscript = "partial then final")
        val session = client.startSession()
        val partials = mutableListOf<String>()
        session.commitAndStop { partials.add(it) }
        assertEquals(listOf("partial then final"), partials)
    }

    @Test
    fun `stub session emits the bridged event sequence on its events flow`() = runTest {
        val client = VoiceFlowClient.makeStub(liveTranscript = "streamed text")
        val session = client.startSession()

        session.events.test {
            // finalize emits TextDelta(isNewResponse=true) -> PartialTranscript,
            // then Status(Idle) -> PhaseChanged(Disconnected).
            session.commitAndStop()

            val partial = awaitItem()
            assertTrue(partial is VoiceFlowEvent.PartialTranscript)
            assertEquals("streamed text", (partial as VoiceFlowEvent.PartialTranscript).text)

            val phase = awaitItem()
            assertTrue(phase is VoiceFlowEvent.PhaseChanged)
            assertEquals(
                VoiceFlowConnectionPhase.Disconnected,
                (phase as VoiceFlowEvent.PhaseChanged).phase,
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stub connectionPhase reports connected before finalize`() = runTest {
        val client = VoiceFlowClient.makeStub()
        val session = client.startSession()
        assertEquals(VoiceFlowConnectionPhase.Connected, session.connectionPhase())
    }

    @Test
    fun `stub transcribe returns bulk transcript and fires partials`() = runTest {
        val client = VoiceFlowClient.makeStub(
            liveTranscript = "live",
            bulkTranscript = "bulk result",
        )
        // transcribe() reads PCM from a WAV; build one via the public-ish round trip.
        val tmp = java.io.File.createTempFile("voiceflow-bulk", ".wav")
        tmp.deleteOnExit()
        com.yage.voiceflowkit.internal.Pcm16WavWriter.writeWav(
            pcm = ByteArray(4800) { 7 },
            out = tmp,
        )
        val partials = mutableListOf<String>()
        val result = client.transcribe(tmp) { partials.add(it) }
        assertEquals("bulk result", result.text)
        assertNotNull(result.requestId)
        assertTrue(result.requestId.isNotEmpty())
        // Mock invokes the partial callback twice.
        assertEquals(listOf("bulk result", "bulk result"), partials)
    }

    @Test
    fun `stub transcribe falls back to liveTranscript when bulk is unset`() = runTest {
        val client = VoiceFlowClient.makeStub(liveTranscript = "fallback text")
        val tmp = java.io.File.createTempFile("voiceflow-fallback", ".wav")
        tmp.deleteOnExit()
        com.yage.voiceflowkit.internal.Pcm16WavWriter.writeWav(ByteArray(4800) { 3 }, out = tmp)
        val result = client.transcribe(tmp)
        assertEquals("fallback text", result.text)
    }

    @Test
    fun `transcribe surfaces AudioConversionFailed for a bad wav`() = runTest {
        val client = VoiceFlowClient.makeStub()
        val tmp = java.io.File.createTempFile("voiceflow-bad", ".wav")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10)) // smaller than a 44-byte header.
        try {
            client.transcribe(tmp)
            throw AssertionError("expected VoiceFlowError.AudioConversionFailed")
        } catch (error: VoiceFlowError) {
            assertEquals(VoiceFlowError.AudioConversionFailed, error)
        }
    }

    @Test
    fun `empty token surfaces MissingToken`() = runTest {
        val client = VoiceFlowClient.makeStub(
            config = VoiceFlowConfig(tokenProvider = { "   " }),
        )
        try {
            client.startSession()
            throw AssertionError("expected VoiceFlowError.MissingToken")
        } catch (error: VoiceFlowError) {
            assertEquals(VoiceFlowError.MissingToken, error)
        }
    }
}
