package com.yage.voiceflow

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.yage.voiceflow.data.SettingsStore
import com.yage.voiceflow.model.AppLanguage
import com.yage.voiceflow.model.RecordingStatus
import com.yage.voiceflow.model.OpenCodeSendStatus
import com.yage.voiceflow.service.OpenCodeClient
import com.yage.voiceflow.ui.record.recordStatusKey
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowMicrophone
import com.yage.voiceflowkit.VoiceFlowRecordingStrategy
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolbarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { unmockkAll(); Dispatchers.resetMain() }

    private fun viewModel(
        application: Application = mockk(relaxed = true),
        openCodeClient: OpenCodeClient = mockk(),
        openCodeConfigured: Boolean = false,
    ): MainViewModel {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.language } returns AppLanguage.English
        every { settings.recordingStrategy } returns VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE
        every { settings.hasToken() } returns false
        every { settings.hasOpenCodePassword() } returns openCodeConfigured
        every { settings.openCodeConnectionVerified } returns openCodeConfigured
        every { settings.openCodeServerURL } returns "https://example.invalid"
        every { settings.openCodeUsername } returns "synthetic"
        every { settings.getOpenCodePassword() } returns "synthetic"
        val mic = mockk<VoiceFlowMicrophone>(relaxed = true)
        every { mic.audioLevel } returns emptyFlow()
        return MainViewModel(application, settings, mockk<VoiceFlowClient>(relaxed = true), mic, openCodeClient)
    }

    @Test fun `empty clear preserves history cursor and previous restores single entry`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onTranscriptEdited("one")
        vm.clearTranscriptToHistory()
        val history = vm.state.value.transcriptHistory
        assertEquals("", vm.state.value.transcript)
        assertTrue(vm.state.value.canNavigatePreviousTranscript)
        vm.clearTranscriptToHistory()
        assertSame(history, vm.state.value.transcriptHistory)
        vm.navigatePreviousTranscript()
        assertEquals("one", vm.state.value.transcript)
        vm.onTranscriptEdited("two")
        vm.clearTranscriptToHistory()
        vm.navigatePreviousTranscript()
        vm.navigatePreviousTranscript()
        assertEquals("one", vm.state.value.transcript)
        vm.onTranscriptEdited("")
        val olderCursor = vm.state.value.transcriptHistory
        vm.clearTranscriptToHistory()
        assertSame(olderCursor, vm.state.value.transcriptHistory)
        vm.clearToolbarFeedback()
    }

    @Test fun `clear and navigation on a fresh empty history do not create entries`() = runTest(dispatcher) {
        val vm = viewModel()
        val history = vm.state.value.transcriptHistory
        vm.clearTranscriptToHistory()
        vm.navigatePreviousTranscript()
        vm.navigateNextTranscript()
        assertSame(history, vm.state.value.transcriptHistory)
        assertEquals("", vm.state.value.transcript)
        assertEquals("record.clear.empty", vm.state.value.toolbarFeedbackKey)
        vm.clearToolbarFeedback()
    }

    @Test fun `empty copy reaches Android clipboard and failure reports failure`() = runTest(dispatcher) {
        val application = mockk<Application>(relaxed = true)
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        val clip = mockk<ClipData>()
        every { application.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns clip
        val vm = viewModel(application)
        assertTrue(vm.copyTranscriptFromToolbar())
        verify(exactly = 1) { ClipData.newPlainText("VoiceFlow transcript", "") }
        verify(exactly = 1) { clipboard.setPrimaryClip(clip) }
        assertEquals("record.clipboard.emptyCopied", vm.state.value.toolbarFeedbackKey)
        every { clipboard.setPrimaryClip(any()) } throws IllegalStateException("synthetic failure")
        assertFalse(vm.copyTranscriptFromToolbar())
        assertEquals("record.clipboard.failed", vm.state.value.toolbarFeedbackKey)
        vm.clearToolbarFeedback()
    }

    @Test fun `sending survives clear restore and navigation without allowing duplicate sends`() = runTest(dispatcher) {
        for (fail in listOf(false, true)) {
            val response = CompletableDeferred<Unit>()
            val client = mockk<OpenCodeClient>()
            coEvery { client.sendTranscript(any(), any(), any(), any()) } coAnswers { response.await() }
            val vm = viewModel(openCodeClient = client, openCodeConfigured = true)
            vm.onTranscriptEdited("older")
            vm.clearTranscriptToHistory()
            vm.onTranscriptEdited("sent text")
            vm.clearToolbarFeedback()
            assertTrue(vm.state.value.canSendToOpenCode)
            vm.sendTranscriptToOpenCode()
            runCurrent()

            fun assertSending() {
                assertEquals(OpenCodeSendStatus.Sending, vm.state.value.openCodeSendStatus)
                assertEquals("record.openCode.sending", recordStatusKey(vm.state.value))
                // This is the actual property bound to the menu's enabled flag.
                assertFalse(vm.state.value.canSendToOpenCode)
            }
            assertSending()
            assertTrue(vm.state.value.canClearTranscript)
            vm.clearTranscriptToHistory()
            assertEquals("", vm.state.value.transcript)
            assertSending()
            vm.navigatePreviousTranscript() // Restore the just-cleared entry.
            assertEquals("sent text", vm.state.value.transcript)
            assertSending()
            vm.navigatePreviousTranscript()
            assertEquals("older", vm.state.value.transcript)
            assertSending()
            vm.navigateNextTranscript()
            assertEquals("sent text", vm.state.value.transcript)
            assertSending()
            vm.sendTranscriptToOpenCode() // Programmatic calls also cannot duplicate the request.
            runCurrent()
            coVerify(exactly = 1) { client.sendTranscript("sent text", any(), any(), any()) }
            advanceTimeBy(2_000)
            runCurrent()
            assertSending()

            if (fail) response.completeExceptionally(IllegalStateException("synthetic failure"))
            else response.complete(Unit)
            runCurrent()
            assertEquals(if (fail) OpenCodeSendStatus.Failed("record.openCode.error.sendFailed")
                else OpenCodeSendStatus.Success, vm.state.value.openCodeSendStatus)
            assertEquals(if (fail) "record.openCode.error.sendFailed" else "record.openCode.sent",
                recordStatusKey(vm.state.value))
            assertTrue(vm.state.value.canSendToOpenCode)
            vm.clearTranscriptToHistory() // Completed statuses may be cleared normally.
            assertEquals(OpenCodeSendStatus.Idle, vm.state.value.openCodeSendStatus)
            vm.clearToolbarFeedback()
        }
    }

    @Test fun `explicit copy expires to final idle status and leaving clears success failure and empty results`() = runTest(dispatcher) {
        val application = mockk<Application>(relaxed = true)
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { application.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns mockk()
        for (text in listOf("text", "")) {
            for (fail in listOf(false, true)) {
                val vm = viewModel(application)
                vm.onTranscriptEdited(text)
                every { clipboard.setPrimaryClip(any()) } just Runs
                vm.copyTranscript() // Seed an old persistent automatic-copy result.
                assertEquals("record.clipboard.copied", recordStatusKey(vm.state.value))
                if (fail) every { clipboard.setPrimaryClip(any()) } throws IllegalStateException("synthetic failure")
                assertEquals(!fail, vm.copyTranscriptFromToolbar())
                val key = when {
                    fail -> "record.clipboard.failed"
                    text.isEmpty() -> "record.clipboard.emptyCopied"
                    else -> "record.clipboard.copied"
                }
                assertEquals(key, recordStatusKey(vm.state.value))
                assertNull(vm.state.value.lastClipboardStatusKey)
                advanceTimeBy(1_999)
                runCurrent()
                assertEquals(key, recordStatusKey(vm.state.value))
                advanceTimeBy(1)
                runCurrent()
                assertEquals(RecordingStatus.Idle.statusKey, recordStatusKey(vm.state.value))

                vm.copyTranscriptFromToolbar()
                assertEquals(key, recordStatusKey(vm.state.value))
                vm.clearToolbarFeedback() // Same cleanup used by Record's onDispose.
                assertEquals(RecordingStatus.Idle.statusKey, recordStatusKey(vm.state.value))
                advanceTimeBy(2_000)
                runCurrent()
                assertEquals(RecordingStatus.Idle.statusKey, recordStatusKey(vm.state.value))
            }
        }
    }

    @Test fun `automatic copy keeps existing persistent status after timeout and page cleanup`() = runTest(dispatcher) {
        val application = mockk<Application>(relaxed = true)
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { application.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns mockk()
        val vm = viewModel(application)
        vm.onTranscriptEdited("automatic transcript")
        for (fail in listOf(false, true)) {
            if (fail) every { clipboard.setPrimaryClip(any()) } throws IllegalStateException("synthetic failure")
            vm.copyTranscript()
            val key = if (fail) "record.clipboard.failed" else "record.clipboard.copied"
            assertNull(vm.state.value.toolbarFeedbackKey)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(key, recordStatusKey(vm.state.value))
            vm.clearToolbarFeedback()
            assertEquals(key, recordStatusKey(vm.state.value))
        }
    }

    @Test fun `clear is disabled during capture but existing rescue gates remain available`() {
        for (status in listOf(RecordingStatus.RequestingPermission, RecordingStatus.Recording, RecordingStatus.Transcribing)) {
            val busy = UiState(recordingStatus = status, hasToken = true, hasRecordingFile = true)
            assertFalse(busy.canClearTranscript)
            assertFalse(busy.canNavigateTranscriptHistory)
            assertTrue(busy.canSaveRecording)
            assertTrue(busy.canResendRecording)
        }
        assertTrue(UiState().canClearTranscript)
    }
}
