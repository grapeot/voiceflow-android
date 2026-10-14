package com.yage.voiceflow

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.yage.voiceflow.data.SettingsStore
import com.yage.voiceflow.model.*
import com.yage.voiceflow.service.CustomActionClient
import com.yage.voiceflow.service.OpenCodeClient
import com.yage.voiceflow.ui.record.recordStatusKey
import com.yage.voiceflowkit.*
import io.mockk.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CustomActionViewModelTest {
    @get:Rule val files = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() { Dispatchers.setMain(dispatcher); mockkStatic(ClipData::class) }
    @After fun tearDown() {
        stores.forEach { it.clear() }
        unmockkAll()
        Dispatchers.resetMain()
    }

    private inner class Fixture(
        hasToken: Boolean = true,
        openCodeConfigured: Boolean = false,
        actionClient: CustomActionClient? = null,
    ) {
        val settings = mockk<SettingsStore>(relaxed = true)
        val action = actionClient ?: mockk<CustomActionClient>()
        val voice = mockk<VoiceFlowClient>(relaxed = true)
        val mic = mockk<VoiceFlowMicrophone>(relaxed = true)
        val openCode = mockk<OpenCodeClient>(relaxed = true)
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        val copies = mutableListOf<CharSequence>()
        var config = CustomActionConfig.default(false)
        val wav = files.newFile().apply { writeText("synthetic audio") }
        var micActive = false
        val vm: MainViewModel

        init {
            every { settings.language } returns AppLanguage.English
            every { settings.recordingStrategy } returns VoiceFlowRecordingStrategy.GROK_BATCH
            every { settings.hasToken() } returns hasToken
            every { settings.getToken() } returns if (hasToken) "synthetic" else ""
            every { settings.customActionConfig } answers { config }
            every { settings.customActionConfig = any() } answers { config = firstArg() }
            every { settings.hasOpenCodePassword() } returns openCodeConfigured
            every { settings.openCodeConnectionVerified } returns openCodeConfigured
            every { settings.openCodeServerURL } returns "https://example.invalid"
            every { settings.openCodeUsername } returns "synthetic"
            every { settings.getOpenCodePassword() } returns "synthetic"
            every { mic.audioLevel } returns emptyFlow()
            every { mic.hasPermission() } returns true
            coEvery { mic.start(any(), any(), any()) } answers {
                micActive = true
                thirdArg<((ByteArray) -> Unit)?>()?.invoke(ByteArray(96_000) { if (it % 2 == 0) 0 else 64 })
            }
            coEvery { mic.stop() } answers { if (micActive) { micActive = false; wav } else null }
            coEvery { voice.transcribe(any<java.io.File>(), any(), any()) } returns TranscriptionResult("recorded source", "synthetic")
            if (actionClient == null) {
                coEvery { action.transform(any(), any(), any(), any(), any()) } returns "polished result"
            }
            val app = mockk<Application>(relaxed = true)
            every { app.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
            every { ClipData.newPlainText(any(), capture(copies)) } returns mockk()
            vm = MainViewModel(app, settings, voice, mic, openCode, action)
            stores += ViewModelStore().also { it.put("vm", vm) }
        }
    }

    @Test fun `real VM blank action ignores absent token and invalid config without side effects`() = runTest(dispatcher) {
        val f = Fixture(hasToken = false)
        f.vm.updateCustomAction(CustomActionConfig("", "", "unknown"))
        for (text in listOf("", " \n")) {
            f.vm.onTranscriptEdited(text)
            clearMocks(f.settings, answers = false)
            val history = f.vm.state.value.transcriptHistory
            assertTrue(f.vm.state.value.canRunCustomAction)
            f.vm.runOrCancelCustomAction(); runCurrent()
            verify(exactly = 0) { f.settings.getToken() }
            verify(exactly = 0) { f.settings.hasToken() }
            coVerify(exactly = 0) { f.action.transform(any(), any(), any(), any(), any()) }
            assertSame(history, f.vm.state.value.transcriptHistory)
            assertEquals(text, f.vm.state.value.transcript)
            assertEquals(CustomActionState.Idle, f.vm.state.value.customActionState)
            assertEquals("record.customAction.empty", recordStatusKey(f.vm.state.value))
            assertTrue(f.copies.isEmpty())
            advanceTimeBy(2_000); runCurrent()
            assertEquals(RecordingStatus.Idle.statusKey, recordStatusKey(f.vm.state.value))
        }
    }

    @Test fun `busy recording and local stopping are checked before blank action or token`() = runTest(dispatcher) {
        val f = Fixture()
        f.vm.startRecording(); runCurrent()
        assertEquals("", f.vm.state.value.transcript)
        clearMocks(f.settings, answers = false)
        f.vm.runOrCancelCustomAction()
        assertNull(f.vm.state.value.toolbarFeedbackKey)
        verify(exactly = 0) { f.settings.getToken() }
        val releaseStop = CompletableDeferred<java.io.File?>()
        coEvery { f.mic.stop() } coAnswers { releaseStop.await() }
        f.vm.stopRecording(); runCurrent()
        assertTrue(f.vm.state.value.localStopInProgress)
        f.vm.runOrCancelCustomAction()
        verify(exactly = 0) { f.settings.getToken() }
        coVerify(exactly = 0) { f.action.transform(any(), any(), any(), any(), any()) }
        releaseStop.complete(null); runCurrent()
    }

    @Test fun `nonempty invalid config and missing token fail locally`() = runTest(dispatcher) {
        val f = Fixture(hasToken = false)
        f.vm.onTranscriptEdited("source")
        f.vm.updateCustomAction(CustomActionConfig("", ""))
        clearMocks(f.settings, answers = false)
        f.vm.runOrCancelCustomAction()
        verify(exactly = 0) { f.settings.getToken() }
        assertEquals(CustomActionState.Failed(CustomActionFailure.NotConfigured), f.vm.state.value.customActionState)
        f.vm.updateCustomAction(CustomActionConfig.default(false))
        f.vm.runOrCancelCustomAction()
        verify(exactly = 1) { f.settings.getToken() }
        coVerify(exactly = 0) { f.action.transform(any(), any(), any(), any(), any()) }
        assertEquals(CustomActionState.Failed(CustomActionFailure.MissingToken), f.vm.state.value.customActionState)
    }

    @Test fun `success preserves source history copies result and invalidates recording tail`() = runTest(dispatcher) {
        val f = Fixture()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        f.copies.clear()
        f.vm.runOrCancelCustomAction(); runCurrent()
        assertEquals("polished result", f.vm.state.value.transcript)
        assertEquals(listOf("polished result", "recorded source"), f.vm.state.value.transcriptHistory.entries.map { it.text })
        assertEquals(listOf("polished result"), f.copies.map { it.toString() })
        assertEquals(CustomActionState.Idle, f.vm.state.value.customActionState)
        assertEquals("record.clipboard.copied", recordStatusKey(f.vm.state.value))
        f.vm.toggleTranscriptMode()
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("polished result\nrecorded source", f.vm.state.value.transcript)
    }

    @Test fun `running locks real mutation entry points but keeps copy and recorded audio save available`() = runTest(dispatcher) {
        val f = Fixture()
        f.vm.onTranscriptEdited("old"); f.vm.clearTranscriptToHistory()
        f.vm.onTranscriptEdited("middle"); f.vm.clearTranscriptToHistory()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        f.vm.navigatePreviousTranscript() // Middle entry has both history directions available.
        assertTrue(f.vm.state.value.canNavigatePreviousTranscript)
        assertTrue(f.vm.state.value.canNavigateNextTranscript)
        val response = CompletableDeferred<String>()
        coEvery { f.action.transform(any(), any(), any(), any(), any()) } coAnswers { response.await() }
        f.vm.runOrCancelCustomAction(); runCurrent()
        val running = f.vm.state.value
        assertEquals("record.customAction.running", recordStatusKey(running))
        assertNull(running.toolbarFeedbackKey)
        assertFalse(running.canStartRecording)
        assertFalse(running.canResendRecording)
        assertFalse(running.canClearTranscript)
        assertFalse(running.canChangeTranscriptMode)
        assertTrue(running.canSaveRecording)
        f.vm.startRecording()
        f.vm.handleDeepLinkStartRecording()
        f.vm.stopRecording()
        f.vm.resendLastRecording()
        f.vm.clearTranscriptToHistory()
        f.vm.navigatePreviousTranscript()
        f.vm.navigateNextTranscript()
        f.vm.toggleTranscriptMode()
        f.vm.onTranscriptEdited("forbidden edit")
        f.vm.clearAIBuilderToken()
        runCurrent()
        assertEquals(running, f.vm.state.value)
        verify(exactly = 0) { f.settings.clearToken() }
        assertTrue(f.vm.copyTranscriptFromToolbar())
        assertEquals("record.customAction.running", recordStatusKey(f.vm.state.value))
        response.complete("result"); runCurrent()
        assertEquals("result", f.vm.state.value.transcript)
    }

    @Test fun `failure keeps document history and clipboard and only exposes a category`() = runTest(dispatcher) {
        val f = Fixture()
        f.vm.onTranscriptEdited("source"); f.vm.copyTranscript()
        val before = f.vm.state.value
        val writes = f.copies.size
        coEvery { f.action.transform(any(), any(), any(), any(), any()) } throws IllegalStateException("PRIVATE_RESPONSE_SENTINEL")
        f.vm.runOrCancelCustomAction(); runCurrent()
        assertEquals("source", f.vm.state.value.transcript)
        assertSame(before.transcriptHistory, f.vm.state.value.transcriptHistory)
        assertEquals(before.lastClipboardStatusKey, f.vm.state.value.lastClipboardStatusKey)
        assertEquals(writes, f.copies.size)
        assertEquals("record.customAction.error.generic", recordStatusKey(f.vm.state.value))
        assertFalse(f.vm.state.value.toString().contains("PRIVATE_RESPONSE_SENTINEL"))
    }

    @Test fun `cancel edit and restart reject late results from the cancelled request`() = runTest(dispatcher) {
        val f = Fixture()
        val response = CompletableDeferred<String>()
        coEvery { f.action.transform(any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { response.await() }
        }
        f.vm.onTranscriptEdited("source")
        f.vm.runOrCancelCustomAction(); runCurrent()
        f.vm.runOrCancelCustomAction()
        assertEquals("record.customAction.cancelled", recordStatusKey(f.vm.state.value))
        assertEquals("source", f.vm.state.value.transcript)
        assertTrue(f.vm.state.value.transcriptHistory.entries.isEmpty())
        assertTrue(f.copies.isEmpty())
        f.vm.onTranscriptEdited("edited after cancel")
        coEvery { f.action.transform(any(), any(), any(), any(), any()) } returns "new result"
        f.vm.runOrCancelCustomAction(); runCurrent()
        val current = f.vm.state.value
        response.complete("late old result"); runCurrent()
        assertEquals(current, f.vm.state.value)
        assertEquals(listOf("new result"), f.copies.map { it.toString() })
    }

    @Test fun `request snapshots settings and clipboard failure does not roll back result`() = runTest(dispatcher) {
        val f = Fixture()
        val response = CompletableDeferred<String>()
        coEvery { f.action.transform(any(), any(), any(), any(), any()) } coAnswers { response.await() }
        f.vm.onTranscriptEdited("source")
        f.vm.runOrCancelCustomAction(); runCurrent()
        f.vm.updateCustomAction(CustomActionConfig("Different", "different instructions", "deepseek-v4-flash"))
        assertEquals(CustomActionState.Running("Polish"), f.vm.state.value.customActionState)
        every { f.clipboard.setPrimaryClip(any()) } throws IllegalStateException("synthetic clipboard failure")
        response.complete("transformed"); runCurrent()
        coVerify(exactly = 1) { f.action.transform("source", CustomActionConfig.default(false).instructions,
            "grok-4-fast", f.vm.aiBuilderEndpoint, "synthetic") }
        assertEquals("transformed", f.vm.state.value.transcript)
        assertEquals(listOf("transformed", "source"), f.vm.state.value.transcriptHistory.entries.map { it.text })
        assertEquals("record.clipboard.failed", recordStatusKey(f.vm.state.value))
    }

    @Test fun `action success preserves an existing Sending request and its duplicate send gate`() = runTest(dispatcher) {
        val f = Fixture(openCodeConfigured = true)
        val sent = CompletableDeferred<Unit>()
        coEvery { f.openCode.sendTranscript(any(), any(), any(), any()) } coAnswers { sent.await() }
        f.vm.onTranscriptEdited("source")
        f.vm.sendTranscriptToOpenCode(); runCurrent()
        f.vm.runOrCancelCustomAction(); runCurrent()
        assertEquals("polished result", f.vm.state.value.transcript)
        assertEquals(OpenCodeSendStatus.Sending, f.vm.state.value.openCodeSendStatus)
        assertEquals("record.openCode.sending", recordStatusKey(f.vm.state.value))
        assertFalse(f.vm.state.value.canSendToOpenCode)
        f.vm.sendTranscriptToOpenCode(); runCurrent()
        coVerify(exactly = 1) { f.openCode.sendTranscript(any(), any(), any(), any()) }
        sent.complete(Unit); runCurrent()
        assertEquals(OpenCodeSendStatus.Success, f.vm.state.value.openCodeSendStatus)
    }

    @Test fun `real client malformed text parts never commit document history or clipboard`() = runTest(dispatcher) {
        val parts = listOf(
            """[{"type":"text","text":"first"},42,{"type":"text","text":"third"}]""",
            """[{"type":"text","text":{"unexpected":"object"}}]""",
            """[{"type":"text","text":42}]""",
        )
        for (content in parts) {
            val call = mockk<Call>(relaxed = true)
            val http = mockk<OkHttpClient>()
            val request = slot<Request>()
            every { http.newCall(capture(request)) } returns call
            every { call.enqueue(any()) } answers {
                firstArg<Callback>().onResponse(call, Response.Builder()
                    .request(request.captured).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"choices":[{"finish_reason":"stop","message":{"content":$content}}]}"""
                        .toResponseBody("application/json".toMediaType()))
                    .build())
            }
            val f = Fixture(actionClient = CustomActionClient(http))
            f.vm.onTranscriptEdited("original source")
            f.vm.clearTranscriptToHistory()
            f.vm.navigatePreviousTranscript()
            f.vm.copyTranscript()
            val before = f.vm.state.value
            val writes = f.copies.toList()
            f.vm.runOrCancelCustomAction(); runCurrent()
            assertEquals(before.transcript, f.vm.state.value.transcript)
            assertSame(before.transcriptHistory, f.vm.state.value.transcriptHistory)
            assertEquals(before.lastClipboardStatusKey, f.vm.state.value.lastClipboardStatusKey)
            assertEquals(writes, f.copies)
            assertEquals(CustomActionState.Failed(CustomActionFailure.InvalidResponse), f.vm.state.value.customActionState)
            assertEquals(CustomActionFailure.InvalidResponse.key, recordStatusKey(f.vm.state.value))
        }
    }
}
