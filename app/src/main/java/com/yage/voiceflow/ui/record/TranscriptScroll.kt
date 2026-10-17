package com.yage.voiceflow.ui.record

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where a programmatic transcript change should leave the editor. */
enum class TranscriptScrollTarget {
    /** User typing, or the text did not change. Do not scroll. */
    None,
    /** Whole-document replacement. Show the beginning. */
    Start,
    /** The new text extends the previous non-empty text. Follow the end. */
    End,
}

/**
 * iOS `RecordView` updateUIView (642–675): a non-empty prefix extension scrolls
 * to the end (typewriter frames, GPT Live partials). Anything else that actually
 * changes the text scrolls to the start (custom action, history jump, a replace
 * mode result that is not an extension, including the first non-empty text).
 * A hand edit never scrolls, even when the typed text extends the old value.
 */
fun transcriptScrollTarget(
    previous: String,
    next: String,
    fromUserEdit: Boolean,
): TranscriptScrollTarget {
    if (fromUserEdit || previous == next) return TranscriptScrollTarget.None
    return if (previous.isNotEmpty() && next.startsWith(previous)) {
        TranscriptScrollTarget.End
    } else {
        TranscriptScrollTarget.Start
    }
}

/**
 * Record column bottom inset. Scaffold already pads the tab bar and system bars.
 * The IME inset is the keyboard overlap from the screen bottom. Take the larger
 * value so the history row sits on the keyboard without adding the two paddings.
 * A zero IME inset leaves the scaffold padding unchanged.
 */
internal fun recordContentBottom(scaffoldBottom: Dp, imeBottom: Dp): Dp =
    maxOf(scaffoldBottom, imeBottom.coerceAtLeast(0.dp))
