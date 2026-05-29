package com.yage.voiceflowkit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the two-layer caption helper. Mirrors the Swift `captionStoreLayersFlash` smoke
 * test: a transient flash hides the persistent layer, then clears itself after the configured
 * duration, revealing the (possibly updated) persistent layer underneath.
 *
 * The flash timer runs on an injected scope backed by [runTest]'s virtual clock so the
 * timing is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamCaptionStoreTest {

    @Test
    fun `visible prefers transient over persistent`() {
        assertEquals("t", StreamCaption(persistent = "p", transient = "t").visible)
        assertEquals("p", StreamCaption(persistent = "p", transient = null).visible)
        assertNull(StreamCaption().visible)
    }

    @Test
    fun `transient flash hides persistent then clears itself`() = runTest {
        val store = StreamCaptionStore(
            transientDurationMs = 3_000L,
            scope = CoroutineScope(coroutineContext),
        )
        store.setPersistent("reconnecting")
        assertEquals("reconnecting", store.caption.value.visible)

        store.flashTransient("restored")
        runCurrent()
        assertEquals("restored", store.caption.value.visible)

        advanceTimeBy(3_001L)
        runCurrent()
        assertEquals("reconnecting", store.caption.value.visible)
    }

    @Test
    fun `re-flashing restarts the timer`() = runTest {
        val store = StreamCaptionStore(
            transientDurationMs = 3_000L,
            scope = CoroutineScope(coroutineContext),
        )
        store.flashTransient("first")
        runCurrent()
        advanceTimeBy(2_000L)
        // Re-flash before the first expires: timer restarts, second value shows.
        store.flashTransient("second")
        runCurrent()
        assertEquals("second", store.caption.value.visible)

        // After only 2s more (4s total since first), the second is still visible
        // because its own 3s window has not elapsed.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals("second", store.caption.value.visible)

        advanceTimeBy(1_001L)
        runCurrent()
        assertNull(store.caption.value.visible)
    }

    @Test
    fun `clear drops both layers`() = runTest {
        val store = StreamCaptionStore(scope = CoroutineScope(coroutineContext))
        store.setPersistent("p")
        store.flashTransient("t")
        runCurrent()
        store.clear()
        assertEquals(StreamCaption(), store.caption.value)
        assertNull(store.caption.value.visible)
    }
}
