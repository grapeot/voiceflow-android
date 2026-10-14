package com.yage.voiceflow

import com.yage.voiceflow.model.CustomActionConfig
import com.yage.voiceflow.model.CustomActionFailure
import com.yage.voiceflow.model.CustomActionState
import com.yage.voiceflow.service.CustomActionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One request owner; all entry points and callbacks run on the VM's main scope. */
internal class CustomActionRunner(
    private val scope: CoroutineScope,
    private val token: () -> String,
    private val currentText: () -> String,
    private val transform: suspend (String, CustomActionConfig, String) -> String,
    private val stateChanged: (CustomActionState) -> Unit,
    private val feedback: (String) -> Unit,
    private val commit: (String, String) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null
    var running = false
        private set

    fun run(source: String, config: CustomActionConfig) {
        if (running) return
        // Must precede config validation and every token read, including hasToken().
        if (source.isBlank()) {
            feedback("record.customAction.empty")
            return
        }
        if (!config.isConfigured) {
            stateChanged(CustomActionState.Failed(CustomActionFailure.NotConfigured))
            return
        }
        val credential = try { token() } catch (_: Exception) { "" }
        if (credential.isBlank()) {
            stateChanged(CustomActionState.Failed(CustomActionFailure.MissingToken))
            return
        }
        val id = ++generation
        running = true
        stateChanged(CustomActionState.Running(config.name.trim()))
        job = scope.launch {
            try {
                val result = transform(source, config, credential)
                if (id != generation) return@launch
                if (result.isBlank()) throw CustomActionError(CustomActionFailure.EmptyResult)
                if (currentText() == source) commit(source, result)
                stateChanged(CustomActionState.Idle)
            } catch (_: CancellationException) {
                if (id == generation) stateChanged(CustomActionState.Idle)
            } catch (error: Exception) {
                if (id == generation) {
                    stateChanged(CustomActionState.Failed(
                        (error as? CustomActionError)?.reason ?: CustomActionFailure.Generic,
                    ))
                }
            } finally {
                if (id == generation) { running = false; job = null }
            }
        }
    }

    fun cancel() {
        ++generation // Revoke result ownership before cancelling a potentially non-cooperative call.
        running = false
        val previous = job
        job = null
        stateChanged(CustomActionState.Idle)
        previous?.cancel()
    }
}
