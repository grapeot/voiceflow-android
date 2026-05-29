package com.yage.voiceflow.model

/**
 * Port of the iOS `TranscriptHistory` struct: a most-recent-first ring of at
 * most [limit] (default 5) finalized transcripts plus a navigation cursor.
 *
 * Semantics replicated exactly from the Swift source:
 *  - [add] trims, ignores blanks, removes any existing duplicate (by text),
 *    prepends the new entry at index 0, truncates to [limit], and resets the
 *    cursor to 0 (the newest entry).
 *  - The cursor moves toward older entries with [navigatePrevious] (index up)
 *    and toward newer entries with [navigateNext] (index down).
 *  - [hasNext] is true when not already at the newest (index > 0).
 *  - [hasPrevious] is true when an older entry exists (index < size - 1).
 *
 * Immutable: navigation/add return a new instance so the value can live in an
 * immutable UiState and be replaced via `copy {}`.
 */
data class TranscriptEntry(
    val text: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

data class TranscriptHistory(
    val entries: List<TranscriptEntry> = emptyList(),
    val currentIndex: Int = 0,
    val limit: Int = 5,
) {
    val hasNext: Boolean
        get() = currentIndex > 0

    val hasPrevious: Boolean
        get() = entries.isNotEmpty() && currentIndex < entries.size - 1

    /** Returns a copy with [text] deduped + prepended, cursor reset to newest. */
    fun add(text: String): TranscriptHistory {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return this

        val deduped = entries.filterNot { it.text == trimmed }
        val updated = (listOf(TranscriptEntry(text = trimmed)) + deduped).take(limit)
        return copy(entries = updated, currentIndex = 0)
    }

    /** Result of a navigation step: the new history plus the text to display. */
    data class NavigationResult(val history: TranscriptHistory, val text: String?)

    /** Move toward older entries. Returns null text if already at the oldest. */
    fun navigatePrevious(): NavigationResult {
        if (!hasPrevious) return NavigationResult(this, null)
        val next = currentIndex + 1
        return NavigationResult(copy(currentIndex = next), entries[next].text)
    }

    /** Move toward newer entries. Returns null text if already at the newest. */
    fun navigateNext(): NavigationResult {
        if (!hasNext) return NavigationResult(this, null)
        val next = currentIndex - 1
        return NavigationResult(copy(currentIndex = next), entries[next].text)
    }
}
