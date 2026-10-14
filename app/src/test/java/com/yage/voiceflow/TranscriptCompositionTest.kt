package com.yage.voiceflow

import com.yage.voiceflow.model.TranscriptComposition
import com.yage.voiceflow.model.TranscriptMode
import org.junit.Assert.*
import org.junit.Test

class TranscriptCompositionTest {
    @Test fun `cumulative snapshots replace only the chunk and preserve unicode base`() {
        val c = TranscriptComposition()
        assertEquals("原文😀", c.begin("原文😀", TranscriptMode.Append))
        assertTrue(c.locksEditor)
        assertEquals("原文😀\nh", c.compose("h"))
        assertEquals("原文😀\nhello", c.compose("hello"))
        assertEquals("原文😀\nHello!", c.compose("Hello!"))
        c.audioPersisted()
        assertEquals("原文😀\nHello!", c.finish("Hello!"))
        assertFalse(c.inFlight)
    }

    @Test fun `empty and newline boundaries compose without an extra separator`() {
        assertEquals("a\n", TranscriptComposition.compose("a\n", ""))
        assertEquals("a\nb", TranscriptComposition.compose("a\n", "b"))
        assertEquals("\n😀", TranscriptComposition.compose("", "\n😀"))
        assertEquals("a\nb", TranscriptComposition.compose("a", "b"))
    }

    @Test fun `resend replaces last tail and failed resend restores boundary repeatedly`() {
        val c = TranscriptComposition()
        c.begin("base", TranscriptMode.Append)
        c.audioPersisted()
        val doc = c.finish("原文😀")
        repeat(2) {
            c.beginResend(doc, TranscriptMode.Append)
            assertEquals("base\nrevised", c.compose("revised"))
            assertEquals(doc, c.settle(c.compose("partial failure")))
            assertFalse(c.locksEditor)
        }
        c.beginResend(doc, TranscriptMode.Append)
        assertEquals("base\nsuccess", c.finish("success"))
    }

    @Test fun `unknown tail after editing is appended without cutting the edited document`() {
        val c = TranscriptComposition()
        c.begin("", TranscriptMode.Replace)
        c.finish("first")
        c.invalidateBoundary()
        c.beginResend("edited😀", TranscriptMode.Append)
        assertEquals("edited😀\nretry", c.finish("retry"))
    }

    @Test fun `rescue of live chunk restores exact leading newline and unicode boundary`() {
        val c = TranscriptComposition()
        c.begin("base\n", TranscriptMode.Append)
        val live = c.compose("\npartial😀")
        c.beginResend(live, TranscriptMode.Append)
        c.audioPersisted()
        assertEquals(live, c.settle(c.compose("bad")))
        c.beginResend(live, TranscriptMode.Append)
        assertEquals("base\ncorrect", c.finish("correct"))
    }

    @Test fun `unpersisted new partial cannot claim the tail of older audio`() {
        val c = TranscriptComposition()
        c.begin("", TranscriptMode.Replace)
        c.finish("old audio")
        c.begin("old audio", TranscriptMode.Append)
        val partial = c.compose("new unpersisted audio")
        c.beginResend(partial, TranscriptMode.Append)
        assertEquals(partial, c.settle(partial)) // stop failed before audioPersisted
        c.beginResend(partial, TranscriptMode.Append)
        assertEquals("$partial\nold retried", c.finish("old retried"))
    }

    @Test fun `persisted failed chunk remains the next resend target`() {
        val c = TranscriptComposition()
        c.begin("base", TranscriptMode.Append)
        c.audioPersisted()
        val partial = c.settle(c.compose("partial"))
        c.beginResend(partial, TranscriptMode.Append)
        assertEquals("base\nretry", c.finish("retry"))
    }

    @Test fun `replace starts fresh and switching to append still replaces prior recording on resend`() {
        val c = TranscriptComposition()
        assertEquals("", c.begin("old", TranscriptMode.Replace))
        assertFalse(c.locksEditor)
        c.audioPersisted()
        c.finish("new")
        c.beginResend("new", TranscriptMode.Append)
        assertEquals("retry", c.finish("retry"))
    }

    @Test fun `superseding a stuck resend keeps the original rollback point`() {
        val c = TranscriptComposition()
        c.begin("base", TranscriptMode.Append)
        c.audioPersisted()
        val original = c.finish("original")
        c.beginResend(original, TranscriptMode.Append)
        c.beginResend(c.compose("partial"), TranscriptMode.Append)
        assertEquals(original, c.settle(c.compose("another failure")))
        c.beginResend(original, TranscriptMode.Append)
        assertEquals("base\nretry", c.finish("retry"))
    }

    @Test fun `manual text during a failed replace recording cannot become a known audio tail`() {
        for (editBeforePersist in listOf(false, true)) {
            val c = TranscriptComposition()
            c.begin("", TranscriptMode.Replace)
            if (editBeforePersist) c.invalidateBoundary()
            c.audioPersisted()
            if (!editBeforePersist) c.invalidateBoundary()
            c.settle("manually edited")
            c.beginResend("manually edited", TranscriptMode.Append)
            assertEquals("manually edited\nretry", c.finish("retry"))
        }
    }

    @Test fun `replace resend rolls back original document and boundary across consecutive rescues`() {
        val c = TranscriptComposition()
        c.begin("", TranscriptMode.Replace)
        c.audioPersisted()
        val original = c.finish("original complete transcript")
        c.beginResend(original, TranscriptMode.Replace)
        c.beginResend(c.compose("first partial"), TranscriptMode.Replace)
        assertEquals(original, c.settle(c.compose("second partial")))
        c.beginResend(original, TranscriptMode.Append)
        assertEquals("corrected", c.finish("corrected"))
    }

    @Test fun `ordinary replace recording failure still keeps visible partial`() {
        val c = TranscriptComposition()
        c.begin("old document", TranscriptMode.Replace)
        c.audioPersisted()
        assertEquals("new partial", c.settle(c.compose("new partial")))
    }
}
