package com.yage.voiceflow

import com.yage.voiceflow.model.OpenCodeSendStatus
import com.yage.voiceflow.model.RecordingStatus
import com.yage.voiceflow.ui.record.recordStatusOverrideKey
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolbarFeedbackTest {
    @Test fun `same feedback resets full two seconds and clears on disposal`() = runTest {
        var key: String? = null
        val feedback = ToolbarFeedback(this) { key = it }
        feedback.show("copy")
        advanceTimeBy(1_999); runCurrent(); assertEquals("copy", key)
        feedback.show("copy")
        advanceTimeBy(1); runCurrent(); assertEquals("copy", key)
        advanceTimeBy(1_999); runCurrent(); assertNull(key)
        feedback.show("mode"); feedback.clear()
        advanceTimeBy(2_000); runCurrent(); assertNull(key)
    }

    @Test fun `active work wins over feedback and normal status resumes when it expires`() {
        val idle = UiState(toolbarFeedbackKey = "copy")
        assertEquals("copy", recordStatusOverrideKey(idle))
        assertNull(recordStatusOverrideKey(idle.copy(toolbarFeedbackKey = null)))
        for (status in listOf(RecordingStatus.Recording, RecordingStatus.Transcribing, RecordingStatus.RequestingPermission)) {
            assertNull(recordStatusOverrideKey(idle.copy(recordingStatus = status)))
        }
        assertNull(recordStatusOverrideKey(idle.copy(openCodeSendStatus = OpenCodeSendStatus.Sending)))
    }
}
