package com.yage.voiceflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ToolbarFeedback(private val scope: CoroutineScope, private val changed: (String?) -> Unit) {
    private var job: Job? = null
    fun show(key: String) {
        clear()
        changed(key)
        job = scope.launch { delay(2_000); changed(null) }
    }
    fun clear() { job?.cancel(); job = null; changed(null) }
}
