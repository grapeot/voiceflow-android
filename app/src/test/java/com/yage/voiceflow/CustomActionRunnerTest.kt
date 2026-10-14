package com.yage.voiceflow

import com.yage.voiceflow.model.CustomActionConfig
import com.yage.voiceflow.model.CustomActionFailure
import com.yage.voiceflow.model.CustomActionState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomActionRunnerTest {
    @Test fun `blank source precedes config and token validation`() = runTest {
        val feedback = mutableListOf<String>()
        val runner = CustomActionRunner(this, { error("token must not be read") }, { "" },
            { _, _, _ -> error("network must not run") }, { error("state must not change") },
            feedback::add, { _, _ -> error("must not commit") })
        runner.run(" \n", CustomActionConfig("", "", "invalid"))
        assertEquals(listOf("record.customAction.empty"), feedback)
    }

    @Test fun `source changed outside UI lock is not overwritten`() = runTest {
        val response = CompletableDeferred<String>()
        var text = "original"
        var state: CustomActionState = CustomActionState.Idle
        val runner = CustomActionRunner(this, { "synthetic" }, { text },
            { _, _, _ -> response.await() }, { state = it }, {}, { _, _ -> error("must not commit") })
        runner.run(text, CustomActionConfig.default(false)); runCurrent()
        text = "externally edited"
        response.complete("transformed"); runCurrent()
        assertEquals("externally edited", text)
        assertEquals(CustomActionState.Idle, state)
        assertFalse(runner.running)
    }

    @Test fun `cancel revokes ownership before a noncooperative completion`() = runTest {
        val response = CompletableDeferred<String>()
        var state: CustomActionState = CustomActionState.Idle
        val runner = CustomActionRunner(this, { "synthetic" }, { "original" },
            { _, _, _ -> withContext(NonCancellable) { response.await() } }, { state = it }, {},
            { _, _ -> error("late result must not commit") })
        runner.run("original", CustomActionConfig.default(false)); runCurrent()
        runner.cancel()
        response.complete("late"); runCurrent()
        assertEquals(CustomActionState.Idle, state)
        assertFalse(runner.running)
    }

    @Test fun `unexpected failure is classified without raw exception content`() = runTest {
        var state: CustomActionState = CustomActionState.Idle
        val runner = CustomActionRunner(this, { "synthetic" }, { "original" },
            { _, _, _ -> error("PRIVATE_RESPONSE_SENTINEL") }, { state = it }, {}, { _, _ -> error("commit") })
        runner.run("original", CustomActionConfig.default(false)); runCurrent()
        assertEquals(CustomActionState.Failed(CustomActionFailure.Generic), state)
    }
}
