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
 * resolution, the recoverable buffer-too-small predicate, and retry preservation.
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
    fun `resolve prefers the longer trimmed text`() {
        // Partial longer -> keep the (untrimmed) partial.
        assertEquals(
            "a long partial transcript",
            RealtimeTranscriptionSupport.resolveFinalizeTranscript("a long partial transcript", "short"),
        )
        // Completed longer -> use the trimmed completed.
        assertEquals(
            "a long completed transcript",
            RealtimeTranscriptionSupport.resolveFinalizeTranscript("short", "  a long completed transcript  "),
        )
    }

    @Test
    fun `resolve keeps partial when equal length`() {
        // length equal -> partial wins (>=).
        assertEquals("abcde", RealtimeTranscriptionSupport.resolveFinalizeTranscript("abcde", "12345"))
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
    fun `accumulator prefers the longer of partial and completed`() {
        val acc = FinalizeTranscriptAccumulator()
        acc.appendDelta("hi")
        acc.setCompleted("a much longer completed transcript")
        assertEquals("a much longer completed transcript", acc.resolvedText)
    }

    @Test
    fun `accumulator preserves and restores across a retry`() {
        val acc = FinalizeTranscriptAccumulator()
        acc.appendDelta("preserved text")
        val snapshot = acc.preserveForRetry()
        acc.reset()
        assertEquals("", acc.resolvedText)
        acc.restoreAfterRetry(snapshot)
        assertEquals("preserved text", acc.resolvedText)
    }
}
