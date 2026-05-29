package com.yage.voiceflowkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Two-layer caption model. Long-lived status (e.g. "Reconnecting…") lives in
 * [persistent]; short confirmations (e.g. "Stream restored.") flash through
 * [transient]. The visible string is `transient ?: persistent` so a flash
 * hides whatever's underneath and clears itself after a fixed duration,
 * revealing the *current* persistent state (which may have changed during
 * the flash window).
 *
 * The store holds localization **keys**, not display strings. The host is
 * responsible for translating keys into localized text — this keeps the kit
 * free of strings catalogs and bilingual maintenance.
 *
 * Port of the Swift `StreamCaption` struct.
 */
data class StreamCaption(
    val persistent: String? = null,
    val transient: String? = null,
) {
    /** The currently visible caption key: [transient] wins over [persistent]. */
    val visible: String? get() = transient ?: persistent
}

/**
 * Observable caption state. Port of the Swift `@MainActor`
 * `StreamCaptionStore` (`ObservableObject` with a `@Published caption`).
 *
 * On Android the observable layer is a [StateFlow] hosts can collect from
 * Compose, a `ViewModel`, or any reactive UI layer. The transient flash
 * timer is driven by a coroutine launched on an internal scope rather than
 * Swift's `Task`; calling [flashTransient] again restarts the timer instead
 * of overlapping.
 *
 * Data layer only — there is no UI here, matching the Swift kit.
 *
 * @param transientDurationMs how long a transient flash stays visible before
 *   it clears itself. Defaults to 3 seconds (Swift's `.seconds(3)`).
 * @param scope coroutine scope used to drive the flash timer. If `null`, an
 *   internal `CoroutineScope(Dispatchers.Main.immediate)` is created so state
 *   mutations stay on the main thread (parallel to Swift's `@MainActor`).
 */
class StreamCaptionStore(
    private val transientDurationMs: Long = 3_000L,
    scope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.Main.immediate)

    private val _caption = MutableStateFlow(StreamCaption())

    /** Observable caption state. */
    val caption: StateFlow<StreamCaption> = _caption.asStateFlow()

    private var transientJob: Job? = null

    /**
     * Set the long-lived caption layer. Pass `null` to clear only the
     * persistent layer (any active transient flash remains).
     */
    fun setPersistent(key: String?) {
        _caption.update { it.copy(persistent = key) }
    }

    /**
     * Flash a transient caption for [transientDurationMs], then clear itself.
     * Restarts the timer if called while another flash is active (rather than
     * overlapping).
     */
    fun flashTransient(key: String) {
        transientJob?.cancel()
        _caption.update { it.copy(transient = key) }
        transientJob = scope.launch {
            delay(transientDurationMs)
            if (isActive) {
                _caption.update { it.copy(transient = null) }
            }
        }
    }

    /** Clear both layers and cancel any pending transient timer. */
    fun clear() {
        transientJob?.cancel()
        transientJob = null
        _caption.value = StreamCaption()
    }
}
