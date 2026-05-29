package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.RealtimeMessageParser
import com.yage.voiceflowkit.internal.RealtimeServerStatus
import com.yage.voiceflowkit.internal.RealtimeTranscriptEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [RealtimeMessageParser] maps inbound WebSocket frames onto internal events
 * exactly as the Swift `RealtimeMessageParser` does: status frames, append vs replace
 * deltas, error fallbacks, and ignored/unknown frames.
 */
class RealtimeMessageParserTest {

    private fun parse(json: String): RealtimeTranscriptEvent? =
        RealtimeMessageParser.parseSocketEvent(JSONObject(json))

    @Test
    fun `session_ready maps to connected status`() {
        val event = parse("""{"type":"session_ready"}""")
        assertEquals(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected), event)
    }

    @Test
    fun `speech_started and speech_stopped map to connected status`() {
        assertEquals(
            RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected),
            parse("""{"type":"speech_started"}"""),
        )
        assertEquals(
            RealtimeTranscriptEvent.Status(RealtimeServerStatus.Connected),
            parse("""{"type":"speech_stopped"}"""),
        )
    }

    @Test
    fun `transcript_delta with text becomes an append delta`() {
        val event = parse("""{"type":"transcript_delta","text":"hello"}""")
        assertEquals(RealtimeTranscriptEvent.TextDelta("hello", isNewResponse = false), event)
    }

    @Test
    fun `transcript_delta falls back to content field`() {
        val event = parse("""{"type":"transcript_delta","content":"world"}""")
        assertEquals(RealtimeTranscriptEvent.TextDelta("world", isNewResponse = false), event)
    }

    @Test
    fun `empty transcript_delta is ignored`() {
        assertNull(parse("""{"type":"transcript_delta","text":""}"""))
        assertNull(parse("""{"type":"transcript_delta"}"""))
    }

    @Test
    fun `transcript_completed becomes a replace delta`() {
        val event = parse("""{"type":"transcript_completed","text":"final transcript"}""")
        assertEquals(
            RealtimeTranscriptEvent.TextDelta("final transcript", isNewResponse = true),
            event,
        )
    }

    @Test
    fun `empty transcript_completed is ignored`() {
        assertNull(parse("""{"type":"transcript_completed","text":""}"""))
    }

    @Test
    fun `session_stopped maps to idle status`() {
        assertEquals(
            RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle),
            parse("""{"type":"session_stopped"}"""),
        )
    }

    @Test
    fun `error uses message field`() {
        val event = parse("""{"type":"error","message":"buffer too small"}""")
        assertEquals(RealtimeTranscriptEvent.ErrorEvent("buffer too small"), event)
    }

    @Test
    fun `error falls back to code then to a default message`() {
        assertEquals(
            RealtimeTranscriptEvent.ErrorEvent("bad_request"),
            parse("""{"type":"error","code":"bad_request"}"""),
        )
        assertEquals(
            RealtimeTranscriptEvent.ErrorEvent("Unknown websocket error"),
            parse("""{"type":"error"}"""),
        )
    }

    @Test
    fun `unknown frame types are ignored`() {
        assertNull(parse("""{"type":"something_new"}"""))
        assertNull(parse("""{}"""))
    }

    @Test
    fun `startControlMessage builds the start frame with vad false and silence default`() {
        val json = JSONObject(RealtimeMessageParser.startControlMessage(model = "gpt-realtime"))
        assertEquals("start", json.getString("type"))
        assertEquals("gpt-realtime", json.getString("model"))
        assertEquals(false, json.getBoolean("vad"))
        assertEquals(1200, json.getInt("silence_duration_ms"))
    }

    @Test
    fun `startControlMessage honors explicit silence duration`() {
        val json = JSONObject(
            RealtimeMessageParser.startControlMessage(
                model = "gpt-realtime",
                vad = false,
                silenceDurationMs = 800,
            ),
        )
        assertEquals(800, json.getInt("silence_duration_ms"))
        assertTrue(json.has("model"))
    }
}
