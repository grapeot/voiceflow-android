package com.yage.voiceflowkit

import com.yage.voiceflowkit.internal.BulkTranscriptionProgress
import com.yage.voiceflowkit.internal.RealtimeServerStatus
import com.yage.voiceflowkit.internal.RealtimeTranscriptEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the bulk-transcribe race (Swift `BulkProgressRegressionTests`,
 * VoiceFlow PR #34): once `Status(Idle)` settles the result, trailing
 * `Disconnected` / `ErrorEvent` / `TextDelta` frames are socket wind-down noise and must
 * not corrupt the final value; a disconnect/error *before* idle is still a real failure.
 */
class BulkTranscriptionProgressTest {

    @Test
    fun `ignores disconnect after idle`() = runTest {
        val progress = BulkTranscriptionProgress()
        progress.handle(RealtimeTranscriptEvent.TextDelta("hello world", isNewResponse = true), null)
        progress.handle(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle), null)
        progress.handle(RealtimeTranscriptEvent.Disconnected, null)

        assertTrue(progress.isFinished())
        assertNull(progress.receivedError())
        assertEquals("hello world", progress.transcript())
    }

    @Test
    fun `ignores error after idle`() = runTest {
        val progress = BulkTranscriptionProgress()
        progress.handle(RealtimeTranscriptEvent.TextDelta("hello", isNewResponse = true), null)
        progress.handle(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle), null)
        progress.handle(RealtimeTranscriptEvent.ErrorEvent("late server error"), null)

        assertNull(progress.receivedError())
        assertEquals("hello", progress.transcript())
    }

    @Test
    fun `ignores trailing delta after idle`() = runTest {
        val progress = BulkTranscriptionProgress()
        progress.handle(RealtimeTranscriptEvent.TextDelta("final", isNewResponse = true), null)
        progress.handle(RealtimeTranscriptEvent.Status(RealtimeServerStatus.Idle), null)
        progress.handle(RealtimeTranscriptEvent.TextDelta(" junk", isNewResponse = false), null)

        assertEquals("final", progress.transcript())
    }

    @Test
    fun `records disconnect before idle as a failure`() = runTest {
        val progress = BulkTranscriptionProgress()
        progress.handle(RealtimeTranscriptEvent.TextDelta("partial", isNewResponse = true), null)
        progress.handle(RealtimeTranscriptEvent.Disconnected, null)

        assertTrue(progress.isFinished())
        assertEquals("WebSocket disconnected", progress.receivedError())
    }

    @Test
    fun `records error before idle`() = runTest {
        val progress = BulkTranscriptionProgress()
        progress.handle(RealtimeTranscriptEvent.ErrorEvent("boom"), null)

        assertTrue(progress.isFinished())
        assertEquals("boom", progress.receivedError())
    }

    @Test
    fun `accumulates append deltas and emits partials`() = runTest {
        val progress = BulkTranscriptionProgress()
        val partials = mutableListOf<String>()
        progress.handle(RealtimeTranscriptEvent.TextDelta("Hello", isNewResponse = false)) { partials.add(it) }
        progress.handle(RealtimeTranscriptEvent.TextDelta(" world", isNewResponse = false)) { partials.add(it) }

        assertEquals("Hello world", progress.transcript())
        assertEquals(listOf("Hello", "Hello world"), partials)
        assertFalse(progress.isFinished())
    }
}
