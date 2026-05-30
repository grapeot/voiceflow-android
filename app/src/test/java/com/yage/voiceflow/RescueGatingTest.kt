package com.yage.voiceflow

import com.yage.voiceflow.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the save/resend rescue gating in [UiState].
 *
 * The "rescue" requirement: once a recording has been persisted to disk
 * ([UiState.hasRecordingFile] == true) the user must always be able to save the
 * audio and to force a re-transcribe, even when the current attempt is stuck in
 * [RecordingStatus.Transcribing]. The previous behavior gated these on
 * `canNavigateTranscriptHistory` (Idle/Ready only), which locked the user out of
 * recovering their audio whenever transcription hung.
 *
 * The gates are pure computed properties on the framework-free [UiState] data
 * class, so we construct states directly.
 */
class RescueGatingTest {

    @Test
    fun `save is enabled while transcribing when a recording file exists`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Transcribing,
            hasRecordingFile = true,
        )
        assertTrue(state.canSaveRecording)
    }

    @Test
    fun `resend is enabled while transcribing when token and recording file exist`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Transcribing,
            hasRecordingFile = true,
            hasToken = true,
        )
        assertTrue(state.canResendRecording)
    }

    @Test
    fun `save is disabled when no recording file exists`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Transcribing,
            hasRecordingFile = false,
        )
        assertFalse(state.canSaveRecording)
    }

    @Test
    fun `resend is disabled when no recording file exists and not recording`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Transcribing,
            hasRecordingFile = false,
            hasToken = true,
        )
        assertFalse(state.canResendRecording)
    }

    @Test
    fun `resend requires a token even when a recording file exists`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Transcribing,
            hasRecordingFile = true,
            hasToken = false,
        )
        assertFalse(state.canResendRecording)
    }

    @Test
    fun `resend is enabled while actively recording even without a persisted file yet`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Recording,
            hasRecordingFile = false,
            hasToken = true,
        )
        assertTrue(state.canResendRecording)
    }

    @Test
    fun `normal completed flow keeps save and resend enabled`() {
        val state = UiState(
            recordingStatus = RecordingStatus.Ready,
            hasRecordingFile = true,
            hasToken = true,
        )
        assertTrue(state.canSaveRecording)
        assertTrue(state.canResendRecording)
    }
}
