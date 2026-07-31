package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.FinalizeTranscriptAccumulator
import com.yage.voiceflowkit.internal.RealtimeTranscriptionSupport
import com.yage.voiceflowkit.internal.TranscriptDeltaReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the stateless transcript helpers ([TranscriptDeltaReducer],
 * [RealtimeTranscriptionSupport]) and the stateful [FinalizeTranscriptAccumulator]
 * reproduce the Swift reduce/resolve semantics: append vs replace deltas, partial-vs-completed
 * resolution and the recoverable buffer-too-small predicate.
 */
class TranscriptHelpersTest {

    // --- TranscriptDeltaReducer ---

    @Test
    fun `delta appends when not a new response`() {
        assertEquals("Hello world", TranscriptDeltaReducer.apply("Hello ", "world", isNewResponse = false))
    }

    @Test
    fun `completed delta replaces the accumulated text`() {
        assertEquals(
            "Final text",
            TranscriptDeltaReducer.apply("partial accumulating", "Final text", isNewResponse = true),
        )
    }

    // --- RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError ---

    @Test
    fun `buffer too small is recoverable case-insensitively`() {
        assertTrue(RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError("buffer too small"))
        assertTrue(RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError("Input BUFFER TOO SMALL to commit"))
    }

    @Test
    fun `other error messages are not recoverable`() {
        assertFalse(RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError("rate limited"))
        assertFalse(RealtimeTranscriptionSupport.isRecoverableBufferTooSmallError(""))
    }

    // --- RealtimeTranscriptionSupport.resolveFinalizeTranscript ---

    @Test
    fun `resolve uses completed when partial is blank`() {
        assertEquals("completed text", RealtimeTranscriptionSupport.resolveFinalizeTranscript("   ", "completed text"))
    }

    @Test
    fun `resolve uses partial when completed is blank or null`() {
        assertEquals("partial text", RealtimeTranscriptionSupport.resolveFinalizeTranscript("partial text", ""))
        assertEquals("partial text", RealtimeTranscriptionSupport.resolveFinalizeTranscript("partial text", null))
    }

    @Test
    fun `resolve treats nonblank completed text as authoritative`() {
        assertEquals(
            "short",
            RealtimeTranscriptionSupport.resolveFinalizeTranscript("a long partial transcript", "  short  "),
        )
    }

    @Test
    fun `resolve trims authoritative completed text`() {
        assertEquals("completed", RealtimeTranscriptionSupport.resolveFinalizeTranscript("partial", " completed "))
    }

    // --- FinalizeTranscriptAccumulator ---

    @Test
    fun `accumulator appends deltas then resolves`() {
        val acc = FinalizeTranscriptAccumulator()
        acc.appendDelta("Hello ")
        acc.appendDelta("there")
        assertEquals("Hello there", acc.resolvedText)
    }

    @Test
    fun `accumulator prefers authoritative completed text`() {
        val acc = FinalizeTranscriptAccumulator()
        acc.appendDelta("a much longer partial transcript")
        acc.setCompleted("done")
        assertEquals("done", acc.resolvedText)
    }

    @Test
    fun `accumulator reset clears all state before retry`() {
        val acc = FinalizeTranscriptAccumulator()
        acc.appendDelta("preserved text")
        acc.setCompleted("completed")
        acc.reset()
        assertEquals("", acc.resolvedText)
    }
}
