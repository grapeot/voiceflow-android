package com.yage.voiceflow.ui.record

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptScrollTest {
    @Test fun `unchanged text does not scroll`() {
        assertEquals(TranscriptScrollTarget.None, transcriptScrollTarget("same", "same", false))
        assertEquals(TranscriptScrollTarget.None, transcriptScrollTarget("", "", false))
    }

    @Test fun `user edits do not scroll even when they extend or replace`() {
        assertEquals(TranscriptScrollTarget.None, transcriptScrollTarget("hel", "hello", true))
        assertEquals(TranscriptScrollTarget.None, transcriptScrollTarget("old doc", "typed", true))
        assertEquals(TranscriptScrollTarget.None, transcriptScrollTarget("hello", "", true))
    }

    @Test fun `typewriter and live partial extensions scroll to the end`() {
        assertEquals(TranscriptScrollTarget.End, transcriptScrollTarget("hel", "hello", false))
        assertEquals(TranscriptScrollTarget.End, transcriptScrollTarget("hello", "hello world", false))
        assertEquals(TranscriptScrollTarget.End, transcriptScrollTarget("base\nhel", "base\nhello", false))
        assertEquals(TranscriptScrollTarget.End, transcriptScrollTarget("你好", "你好世界", false))
    }

    @Test fun `whole replacements and the first non-empty text scroll to the start`() {
        assertEquals(TranscriptScrollTarget.Start, transcriptScrollTarget("", "chunk text", false))
        assertEquals(TranscriptScrollTarget.Start, transcriptScrollTarget("old document", "polished", false))
        assertEquals(TranscriptScrollTarget.Start, transcriptScrollTarget("current", "older history", false))
        assertEquals(TranscriptScrollTarget.Start, transcriptScrollTarget("hello world", "hello", false))
        assertEquals(TranscriptScrollTarget.Start, transcriptScrollTarget("visible", "", false))
    }

    @Test fun `a shared fragment that is not a full prefix is a replacement`() {
        assertEquals(TranscriptScrollTarget.Start, transcriptScrollTarget("hello", "help", false))
    }

    @Test fun `ime inset replaces scaffold padding instead of stacking`() {
        assertEquals(48.dp, recordContentBottom(48.dp, 0.dp))
        assertEquals(48.dp, recordContentBottom(48.dp, (-4).dp))
        assertEquals(320.dp, recordContentBottom(128.dp, 320.dp))
        assertEquals(128.dp, recordContentBottom(128.dp, 40.dp))
    }
}
