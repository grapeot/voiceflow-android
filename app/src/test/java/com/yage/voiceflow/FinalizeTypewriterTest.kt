package com.yage.voiceflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the stop->finalize typewriter logic in [MainViewModel].
 *
 * The ViewModel itself needs the Android framework (AndroidViewModel), so these
 * tests exercise the two pieces that carry the actual behavior and are framework
 * free: the pure [applyStreamedTranscript] reconciliation helper, and the
 * channel-drain pattern that defeats StateFlow conflation. The latter is
 * reproduced here with the same primitives the ViewModel uses (a non-conflating
 * UNLIMITED Channel drained by a single consumer that pushes into a MutableState
 * Flow), proving every delta becomes its own ordered state value.
 */
class FinalizeTypewriterTest {

    // --- applyStreamedTranscript: append-only reconciliation ---

    @Test
    fun `forward append returns the incoming superset`() {
        assertEquals("hello world", applyStreamedTranscript("hello", "hello world"))
    }

    @Test
    fun `identical snapshot is a no-op returning the same instance`() {
        val current = "hello world"
        // Same content -> returns `current` so the consumer can skip a recomposition.
        assertSame(current, applyStreamedTranscript(current, "hello world"))
    }

    @Test
    fun `prefix rewrite replaces with the incoming snapshot`() {
        // Backend re-segments earlier text: not a prefix of current -> replace.
        assertEquals("Hello, world.", applyStreamedTranscript("hello world", "Hello, world."))
    }

    @Test
    fun `growth from empty returns the incoming`() {
        assertEquals("a", applyStreamedTranscript("", "a"))
    }

    // --- channel drain: ordered, non-conflating delivery ---

    @Test
    fun `every delta is delivered in order through the non-conflating channel`() = runTest {
        val scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(scheduler)

        // Mirror the ViewModel: conflating state + UNLIMITED channel + single drain.
        val state = MutableStateFlow("")
        val seen = mutableListOf<String>()
        val channel = Channel<String>(Channel.UNLIMITED)

        val consumer = launch(dispatcher) {
            for (snapshot in channel) {
                state.update { current ->
                    val next = applyStreamedTranscript(current, snapshot)
                    if (next == current) current else next
                }
                seen.add(state.value)
                delay(12)
            }
        }

        // Burst-produce growing snapshots, as finalize does from many IO coroutines.
        val deltas = listOf("h", "he", "hel", "hell", "hello", "hello w", "hello wo", "hello world")
        for (d in deltas) channel.trySend(d)
        channel.close()

        consumer.join()

        // No conflation: the consumer observed every distinct snapshot, in order.
        assertEquals(deltas, seen)
        assertEquals("hello world", state.value)
    }

    @Test
    fun `duplicate snapshots do not produce extra state churn`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val state = MutableStateFlow("")
        val emissions = mutableListOf<String>()
        val channel = Channel<String>(Channel.UNLIMITED)

        val consumer = launch(dispatcher) {
            for (snapshot in channel) {
                val before = state.value
                state.update { current ->
                    val next = applyStreamedTranscript(current, snapshot)
                    if (next == current) current else next
                }
                if (state.value != before) emissions.add(state.value)
                delay(12)
            }
        }

        // "hi" repeated should only move state once.
        listOf("hi", "hi", "hi there").forEach { channel.trySend(it) }
        channel.close()
        consumer.join()

        assertEquals(listOf("hi", "hi there"), emissions)
    }

    @Test
    fun `finalize deltas update transcript but clipboard writes only once after final text`() {
        var transcript = ""
        val writes = mutableListOf<String>()

        listOf("h", "he", "hel", "hell", "hello").forEach { partial ->
            transcript = applyStreamedTranscript(transcript, partial)
        }

        assertEquals("hello", transcript)
        assertEquals(emptyList<String>(), writes)

        val copied = copyTranscriptIfPresent(transcript) { text ->
            writes.add(text)
            true
        }

        assertEquals(true, copied)
        assertEquals(listOf("hello"), writes)
    }

    @Test
    fun `blank transcript skips clipboard writes`() {
        val writes = mutableListOf<String>()

        val copied = copyTranscriptIfPresent("  \n") { text ->
            writes.add(text)
            true
        }

        assertEquals(null, copied)
        assertEquals(emptyList<String>(), writes)
    }
}
