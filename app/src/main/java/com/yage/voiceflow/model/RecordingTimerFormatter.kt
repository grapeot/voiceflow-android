package com.yage.voiceflow.model

import java.util.Locale

/**
 * Port of the iOS `RecordingTimerFormatter` enum. Formats an elapsed-seconds
 * count as "MM:SS", clamping negatives to zero. Uses [Locale.US] so the digit
 * grouping is stable regardless of the active UI language.
 */
object RecordingTimerFormatter {
    fun format(elapsedSeconds: Int): String {
        val clamped = elapsedSeconds.coerceAtLeast(0)
        val minutes = clamped / 60
        val seconds = clamped % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
