package com.yage.voiceflow

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.yage.voiceflow.data.SettingsStore
import com.yage.voiceflow.model.AppLanguage
import com.yage.voiceflow.model.RecordingStatus
import com.yage.voiceflow.model.TranscriptMode
import com.yage.voiceflow.ui.record.recordStatusKey
import com.yage.voiceflowkit.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class TranscriptModeViewModelTest {
    @get:Rule val files = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(ClipData::class)
    }

    @After fun tearDown() {
        stores.forEach { it.clear() }
        unmockkAll()
        Dispatchers.resetMain()
    }

    private inner class Fixture(val strategy: VoiceFlowRecordingStrategy = VoiceFlowRecordingStrategy.GPT_LIVE_TRANSCRIBE) {
        val settings = mockk<SettingsStore>(relaxed = true)
        val client = mockk<VoiceFlowClient>(relaxed = true)
        val mic = mockk<VoiceFlowMicrophone>(relaxed = true)
        val copies = mutableListOf<CharSequence>()
        val session = mockk<VoiceFlowSession>(relaxed = true)
        val events = MutableSharedFlow<VoiceFlowEvent>(extraBufferCapacity = 10)
        val wav = files.newFile().apply { writeText("synthetic audio") }
        var speech = true
        var result = "chunk text"
        var microphoneActive = false
        val vm: MainViewModel

        init {
            every { settings.language } returns AppLanguage.English
            every { settings.recordingStrategy } returns strategy
            every { settings.hasToken() } returns true
            every { settings.getToken() } returns "synthetic"
            every { mic.audioLevel } returns emptyFlow()
            every { mic.hasPermission() } returns true
            coEvery { mic.start(any(), any(), any()) } answers {
                microphoneActive = true
                if (speech) thirdArg<((ByteArray) -> Unit)?>()?.invoke(ByteArray(96_000) { if (it % 2 == 0) 0 else 64 })
            }
            coEvery { mic.stop() } answers {
                if (microphoneActive) { microphoneActive = false; wav } else null
            }
            every { session.events } returns events
            coEvery { client.startSession(any()) } returns session
            coEvery { session.commitAndStop(any()) } answers { result }
            coEvery { client.transcribe(any<java.io.File>(), any(), any()) } answers { TranscriptionResult(result, "synthetic") }
            val app = mockk<Application>(relaxed = true)
            every { app.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockk<ClipboardManager>(relaxed = true)
            every { ClipData.newPlainText(any(), capture(copies)) } returns mockk()
            vm = MainViewModel(app, settings, client, mic)
            stores += ViewModelStore().also { it.put("vm", vm) }
        }

        fun appendBase(text: String = "base😀") {
            vm.onTranscriptEdited(text)
            vm.toggleTranscriptMode()
        }
    }

    @Test fun `mode is session only and reports current mode for two seconds`() = runTest(dispatcher) {
        val f = Fixture()
        assertEquals(TranscriptMode.Replace, f.vm.state.value.transcriptMode)
        f.vm.toggleTranscriptMode()
        assertEquals(TranscriptMode.Append, f.vm.state.value.transcriptMode)
        assertEquals("record.transcriptMode.appendSelected", recordStatusKey(f.vm.state.value))
        advanceTimeBy(2_000); runCurrent()
        assertEquals(RecordingStatus.Idle.statusKey, recordStatusKey(f.vm.state.value))
        assertEquals(TranscriptMode.Replace, Fixture().vm.state.value.transcriptMode)
    }

    @Test fun `start captures mode before permission and locks it until completion`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        val permission = CompletableDeferred<Boolean>()
        f.vm.setMicPermissionRequester { permission.await() }
        f.vm.startRecording()
        f.vm.toggleTranscriptMode()
        f.vm.onTranscriptEdited("must not overwrite base")
        runCurrent()
        assertEquals(TranscriptMode.Append, f.vm.state.value.transcriptMode)
        assertFalse(f.vm.state.value.canChangeTranscriptMode)
        permission.complete(true); runCurrent()
        assertEquals("base😀", f.vm.state.value.transcript)
        assertTrue(f.vm.state.value.chunkLocked)
        f.vm.stopRecording(); runCurrent()
        assertEquals("base😀\nchunk text", f.vm.state.value.transcript)
        assertTrue(f.vm.state.value.canChangeTranscriptMode)
    }

    @Test fun `all strategies commit whole documents to history and clipboard and resend replaces tail`() = runTest(dispatcher) {
        for (strategy in VoiceFlowRecordingStrategy.entries) {
            val f = Fixture(strategy)
            f.appendBase()
            f.vm.startRecording(); runCurrent()
            assertTrue(f.vm.state.value.canResendRecording)
            f.vm.stopRecording(); runCurrent()
            assertEquals("base😀\nchunk text", f.vm.state.value.transcript)
            assertEquals(f.vm.state.value.transcript, f.vm.state.value.transcriptHistory.currentEntry)
            assertEquals(f.vm.state.value.transcript, f.copies.last().toString())
            f.result = "second chunk"
            f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
            assertEquals("base😀\nchunk text\nsecond chunk", f.vm.state.value.transcript)
            f.result = "retried"
            f.vm.resendLastRecording(); runCurrent()
            assertEquals("base😀\nchunk text\nretried", f.vm.state.value.transcript)
            assertEquals(f.vm.state.value.transcript, f.copies.last().toString())
        }
    }

    @Test fun `default replace starts fresh and append resend replaces that whole recording`() = runTest(dispatcher) {
        val f = Fixture()
        f.vm.onTranscriptEdited("old document")
        f.vm.startRecording(); runCurrent()
        assertEquals("", f.vm.state.value.transcript)
        f.vm.stopRecording(); runCurrent()
        assertEquals("chunk text", f.vm.state.value.transcript)
        f.vm.toggleTranscriptMode()
        f.result = "corrected"
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("corrected", f.vm.state.value.transcript)
    }

    @Test fun `gpt live partial still overwrites a hand edit during recording`() = runTest(dispatcher) {
        val f = Fixture()
        f.vm.startRecording(); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("hello")); runCurrent()
        f.vm.onTranscriptEdited("user edit")
        assertEquals("user edit", f.vm.state.value.transcript)
        val history = f.vm.state.value.transcriptHistory
        val copies = f.copies.size
        f.events.emit(VoiceFlowEvent.PartialTranscript("hello world")); runCurrent()
        assertEquals("hello world", f.vm.state.value.transcript)
        assertSame(history, f.vm.state.value.transcriptHistory)
        assertEquals(copies, f.copies.size)
        f.vm.stopRecording(); runCurrent()
    }

    @Test fun `live and finalize cumulative snapshots never append duplicates and remain frame ordered`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("hel")); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("hello")); runCurrent()
        assertEquals("base😀\nhello", f.vm.state.value.transcript)
        assertTrue(f.copies.isEmpty())
        coEvery { f.session.commitAndStop(any()) } answers {
            firstArg<((String) -> Unit)?>()?.invoke("Hello")
            firstArg<((String) -> Unit)?>()?.invoke("Hello world")
            "Hello world!"
        }
        f.vm.stopRecording(); runCurrent()
        assertEquals("base😀\nHello", f.vm.state.value.transcript)
        assertTrue(f.copies.isEmpty())
        advanceTimeBy(12); runCurrent()
        assertEquals("base😀\nHello world", f.vm.state.value.transcript)
        advanceTimeBy(12); runCurrent()
        assertEquals("base😀\nHello world!", f.vm.state.value.transcript)
        assertEquals(listOf("base😀\nHello world!"), f.copies.map { it.toString() })
    }

    @Test fun `realtime bulk fallback composes on base after draining old finalize frames`() = runTest(dispatcher) {
        val f = Fixture(VoiceFlowRecordingStrategy.OPENAI_REALTIME)
        f.appendBase()
        coEvery { f.session.commitAndStop(any()) } answers {
            firstArg<((String) -> Unit)?>()?.invoke("old partial")
            ""
        }
        val response = CompletableDeferred<TranscriptionResult>()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            thirdArg<((String) -> Unit)?>()?.invoke("bulk partial")
            response.await()
        }
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        advanceTimeBy(12); runCurrent()
        assertEquals("base😀\nbulk partial", f.vm.state.value.transcript)
        response.complete(TranscriptionResult("bulk final", "synthetic")); runCurrent()
        assertEquals("base😀\nbulk final", f.vm.state.value.transcript)
        assertEquals("base😀\nbulk final", f.vm.state.value.transcriptHistory.currentEntry)
    }

    @Test fun `failed resend restores document and boundary for a successful retry`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val original = f.vm.state.value.transcript
        val history = f.vm.state.value.transcriptHistory
        val copies = f.copies.size
        val response = CompletableDeferred<TranscriptionResult>()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            thirdArg<((String) -> Unit)?>()?.invoke("retry partial")
            response.await()
        }
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("base😀\nretry partial", f.vm.state.value.transcript)
        response.completeExceptionally(IllegalStateException("synthetic failure")); runCurrent()
        assertEquals(original, f.vm.state.value.transcript)
        assertSame(history, f.vm.state.value.transcriptHistory)
        assertEquals(copies, f.copies.size)
        assertFalse(f.vm.state.value.chunkLocked)
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } returns TranscriptionResult("retry final", "synthetic")
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("base😀\nretry final", f.vm.state.value.transcript)
    }

    @Test fun `edits history and clear invalidate resend boundary without deleting user text`() = runTest(dispatcher) {
        for (change in listOf("edit", "history", "clear")) {
            val f = Fixture()
            f.appendBase()
            f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
            f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
            when (change) {
                "edit" -> f.vm.onTranscriptEdited("edited😀")
                "history" -> f.vm.navigatePreviousTranscript()
                "clear" -> f.vm.clearTranscriptToHistory()
            }
            val edited = f.vm.state.value.transcript
            f.result = "retried"
            f.vm.resendLastRecording(); runCurrent()
            assertEquals(if (edited.isEmpty()) "retried" else "$edited\nretried", f.vm.state.value.transcript)
        }
    }

    @Test fun `old session events and finalize callback cannot modify a new recording`() = runTest(dispatcher) {
        val f = Fixture()
        var oldCollector: FlowCollector<VoiceFlowEvent>? = null
        every { f.session.events } returns object : Flow<VoiceFlowEvent> {
            override suspend fun collect(collector: FlowCollector<VoiceFlowEvent>) {
                oldCollector = collector
                awaitCancellation()
            }
        }
        var oldFinalize: ((String) -> Unit)? = null
        coEvery { f.session.commitAndStop(any()) } answers { oldFinalize = firstArg(); "old final" }
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val newer = mockk<VoiceFlowSession>(relaxed = true)
        every { newer.events } returns emptyFlow()
        coEvery { f.client.startSession(any()) } returns newer
        f.vm.startRecording(); runCurrent()
        val before = f.vm.state.value
        oldCollector!!.emit(VoiceFlowEvent.PartialTranscript("stale text"))
        oldCollector!!.emit(VoiceFlowEvent.PhaseChanged(VoiceFlowConnectionPhase.Disconnected))
        oldFinalize!!("stale finalize")
        runCurrent()
        assertEquals(before, f.vm.state.value)
        f.vm.handleAppStopped(); runCurrent()
    }

    @Test fun `background cancels failed resend restores document and rejects late bulk callbacks`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val original = f.vm.state.value.transcript
        var late: ((String) -> Unit)? = null
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            late = thirdArg()
            late!!("partial")
            awaitCancellation()
        }
        f.vm.resendLastRecording(); runCurrent()
        f.vm.handleAppStopped(); runCurrent()
        assertEquals(original, f.vm.state.value.transcript)
        assertFalse(f.vm.state.value.chunkInFlight)
        assertTrue(f.vm.state.value.canResendRecording)
        late!!("late after cancel"); runCurrent()
        assertEquals(original, f.vm.state.value.transcript)
    }

    @Test fun `no signal and failed stop preserve append base and unlock controls`() = runTest(dispatcher) {
        for (failStop in listOf(false, true)) {
            val f = Fixture()
            f.appendBase()
            f.speech = false
            if (failStop) coEvery { f.mic.stop() } throws IllegalStateException("synthetic failure")
            f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
            assertEquals("base😀", f.vm.state.value.transcript)
            assertFalse(f.vm.state.value.chunkLocked)
            assertTrue(f.vm.state.value.canChangeTranscriptMode)
            assertTrue(f.copies.isEmpty())
            coVerify(exactly = 0) { f.session.commitAndStop(any()) }
        }
    }

    @Test fun `background during recording preserves visible chunk for a replacing resend`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("visible partial")); runCurrent()
        f.vm.handleAppStopped(); runCurrent()
        assertEquals("base😀\nvisible partial", f.vm.state.value.transcript)
        assertFalse(f.vm.state.value.chunkLocked)
        assertTrue(f.vm.state.value.canSaveRecording)
        assertTrue(f.vm.state.value.canResendRecording)
        f.vm.handleAppStarted()
        f.result = "recovered"
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("base😀\nrecovered", f.vm.state.value.transcript)
    }

    @Test fun `resend during active recording replaces its visible chunk`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("live partial")); runCurrent()
        f.result = "rescued"
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("base😀\nrescued", f.vm.state.value.transcript)
        assertFalse(f.vm.state.value.chunkLocked)
        assertEquals("base😀\nrescued", f.copies.last().toString())
    }

    @Test fun `late result from superseded resend cannot alter the current document`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val oldResponse = CompletableDeferred<TranscriptionResult>()
        var oldPartial: ((String) -> Unit)? = null
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            oldPartial = thirdArg()
            withContext(NonCancellable) { oldResponse.await() }
        }
        f.vm.resendLastRecording(); runCurrent()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } returns TranscriptionResult("current", "synthetic")
        f.vm.resendLastRecording(); runCurrent()
        val current = f.vm.state.value
        oldPartial!!("stale partial")
        oldResponse.complete(TranscriptionResult("stale result", "synthetic"))
        runCurrent()
        assertEquals(current, f.vm.state.value)
        assertEquals("base😀\ncurrent", current.transcript)
    }

    @Test fun `blank resend is failure and preserves the document and retry boundary`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val original = f.vm.state.value.transcript
        f.result = "  "
        f.vm.resendLastRecording(); runCurrent()
        assertEquals(original, f.vm.state.value.transcript)
        assertFalse(f.vm.state.value.chunkInFlight)
        assertEquals(RecordingStatus.Idle, f.vm.state.value.recordingStatus)
        f.result = "retry"
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("base😀\nretry", f.vm.state.value.transcript)
    }

    @Test fun `startup failure and background during permission do not leave a locked document`() = runTest(dispatcher) {
        val failed = Fixture()
        failed.appendBase()
        coEvery { failed.client.updateConfig(any()) } throws IllegalStateException("synthetic failure")
        failed.vm.startRecording(); runCurrent()
        assertEquals("base😀", failed.vm.state.value.transcript)
        assertFalse(failed.vm.state.value.chunkInFlight)
        assertTrue(failed.vm.state.value.canChangeTranscriptMode)

        val pending = Fixture()
        pending.appendBase()
        val permission = CompletableDeferred<Boolean>()
        pending.vm.setMicPermissionRequester { permission.await() }
        pending.vm.startRecording(); runCurrent()
        pending.vm.handleAppStopped(); runCurrent()
        permission.complete(true); runCurrent()
        assertEquals("base😀", pending.vm.state.value.transcript)
        assertTrue(pending.vm.state.value.canChangeTranscriptMode)
        coVerify(exactly = 0) { pending.client.startSession(any()) }
    }

    @Test fun `old background cancel cannot settle a foreground resend before or after it succeeds`() = runTest(dispatcher) {
        for (releaseAfterSuccess in listOf(false, true)) {
            val f = Fixture()
            f.appendBase()
            f.vm.startRecording(); runCurrent()
            f.events.emit(VoiceFlowEvent.PartialTranscript("live partial")); runCurrent()
            val cancelling = CompletableDeferred<Unit>()
            val releaseCancel = CompletableDeferred<Unit>()
            coEvery { f.session.cancel() } coAnswers {
                cancelling.complete(Unit)
                releaseCancel.await()
            }
            f.vm.handleAppStopped(); runCurrent()
            assertTrue(cancelling.isCompleted)
            val response = CompletableDeferred<TranscriptionResult>()
            coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
                thirdArg<((String) -> Unit)?>()?.invoke("new partial")
                response.await()
            }
            f.vm.handleAppStarted()
            f.vm.resendLastRecording(); runCurrent()
            assertEquals("base😀\nnew partial", f.vm.state.value.transcript)
            assertTrue(f.vm.state.value.chunkLocked)
            val waiting = f.vm.state.value
            if (!releaseAfterSuccess) {
                releaseCancel.complete(Unit); runCurrent()
                assertEquals(waiting, f.vm.state.value)
                assertTrue(f.copies.isEmpty())
            }
            response.complete(TranscriptionResult("recovered", "synthetic")); runCurrent()
            assertEquals("base😀\nrecovered", f.vm.state.value.transcript)
            assertEquals("base😀\nrecovered", f.vm.state.value.transcriptHistory.currentEntry)
            assertEquals(listOf("base😀\nrecovered"), f.copies.map { it.toString() })
            val completed = f.vm.state.value
            if (releaseAfterSuccess) {
                releaseCancel.complete(Unit); runCurrent()
                assertEquals(completed, f.vm.state.value)
                assertEquals(listOf("base😀\nrecovered"), f.copies.map { it.toString() })
            }
        }
    }

    @Test fun `first recording stop gates duplicate calls then registers WAV before network cleanup`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("live partial")); runCurrent()
        assertFalse(f.vm.state.value.hasRecordingFile) // No older WAV can mask loss of this recording.
        val releaseStop = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        var stopCalls = 0
        coEvery { f.mic.stop() } coAnswers {
            if (++stopCalls > 1) return@coAnswers null // Real recorder has already been detached.
            releaseStop.await()
            f.wav
        }
        coEvery { f.session.cancel() } coAnswers { cancelling.complete(Unit); releaseCancel.await() }
        val response = CompletableDeferred<TranscriptionResult>()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            assertEquals(f.wav, firstArg<java.io.File>())
            response.await()
        }
        f.vm.handleAppStopped(); runCurrent()
        f.vm.handleAppStarted()
        assertTrue(f.vm.state.value.localStopInProgress)
        assertFalse(f.vm.state.value.canStartRecording)
        assertFalse(f.vm.state.value.canStopRecording)
        assertFalse(f.vm.state.value.canResendRecording)
        val stopping = f.vm.state.value
        f.vm.resendLastRecording()
        f.vm.stopRecording()
        f.vm.startRecording()
        runCurrent()
        assertEquals(stopping, f.vm.state.value)
        assertEquals(1, stopCalls)
        coVerify(exactly = 0) { f.client.transcribe(any<java.io.File>(), any(), any()) }
        releaseStop.complete(Unit); runCurrent()
        assertTrue(cancelling.isCompleted) // Old network cancel is still suspended.
        assertFalse(f.vm.state.value.localStopInProgress)
        assertTrue(f.vm.state.value.canSaveRecording)
        assertTrue(f.vm.state.value.canResendRecording)
        f.vm.resendLastRecording(); runCurrent()
        response.complete(TranscriptionResult("recovered", "synthetic")); runCurrent()
        assertEquals("base😀\nrecovered", f.vm.state.value.transcript)
        assertEquals(f.vm.state.value.transcript, f.vm.state.value.transcriptHistory.currentEntry)
        assertEquals(listOf("base😀\nrecovered"), f.copies.map { it.toString() })
        val completed = f.vm.state.value
        releaseCancel.complete(Unit); runCurrent()
        assertEquals(completed, f.vm.state.value)
        assertEquals(1, stopCalls)
    }

    @Test fun `old background audio drain cannot invalidate a foreground rescue`() = runTest(dispatcher) {
        val f = Fixture()
        val releaseAudio = CompletableDeferred<Unit>()
        coEvery { f.session.sendAudioChunk(any()) } coAnswers { releaseAudio.await() }
        f.appendBase()
        f.vm.startRecording(); runCurrent()
        f.events.emit(VoiceFlowEvent.PartialTranscript("live partial")); runCurrent()
        f.vm.handleAppStopped(); runCurrent()
        assertTrue(f.vm.state.value.hasRecordingFile)
        val response = CompletableDeferred<TranscriptionResult>()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers { response.await() }
        f.vm.handleAppStarted()
        f.vm.resendLastRecording(); runCurrent()
        val waiting = f.vm.state.value
        releaseAudio.complete(Unit); runCurrent()
        assertEquals(waiting, f.vm.state.value)
        response.complete(TranscriptionResult("recovered", "synthetic")); runCurrent()
        assertEquals("base😀\nrecovered", f.vm.state.value.transcript)
        assertEquals(listOf("base😀\nrecovered"), f.copies.map { it.toString() })
    }

    @Test fun `late startup error after cancel cannot settle a new recording`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        coEvery { f.mic.start(any(), any(), any()) } throws IllegalStateException("synthetic startup failure")
        val releaseCancel = CompletableDeferred<Unit>()
        coEvery { f.session.cancel() } coAnswers { withContext(NonCancellable) { releaseCancel.await() } }
        f.vm.startRecording(); runCurrent()
        f.vm.handleAppStopped(); runCurrent()
        f.vm.handleAppStarted()
        val newSession = mockk<VoiceFlowSession>(relaxed = true)
        every { newSession.events } returns emptyFlow()
        coEvery { f.client.startSession(any()) } returns newSession
        coEvery { f.mic.start(any(), any(), any()) } returns Unit
        f.vm.startRecording(); runCurrent()
        val current = f.vm.state.value
        releaseCancel.complete(Unit); runCurrent()
        assertEquals(current, f.vm.state.value)
        assertEquals(RecordingStatus.Recording, f.vm.state.value.recordingStatus)
        assertTrue(f.vm.state.value.chunkLocked)
        assertNull(f.vm.recordErrorKey.value)
        f.vm.handleAppStopped(); runCurrent()
    }

    @Test fun `late recording rescue error after cancel cannot settle a newer attempt`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        f.vm.startRecording(); runCurrent()
        coEvery { f.mic.stop() } throws IllegalStateException("synthetic stop failure")
        val releaseCancel = CompletableDeferred<Unit>()
        coEvery { f.session.cancel() } coAnswers { withContext(NonCancellable) { releaseCancel.await() } }
        f.vm.resendLastRecording(); runCurrent()
        f.vm.handleAppStopped(); runCurrent()
        f.vm.handleAppStarted()
        val newSession = mockk<VoiceFlowSession>(relaxed = true)
        every { newSession.events } returns emptyFlow()
        coEvery { f.client.startSession(any()) } returns newSession
        coEvery { f.mic.stop() } returns f.wav
        f.vm.startRecording(); runCurrent()
        val current = f.vm.state.value
        releaseCancel.complete(Unit); runCurrent()
        assertEquals(current, f.vm.state.value)
        assertTrue(f.vm.state.value.chunkLocked)
        assertNull(f.vm.recordErrorKey.value)
        f.vm.handleAppStopped(); runCurrent()
    }

    @Test fun `replace partial followed by exception or blank restores original and its boundary`() = runTest(dispatcher) {
        for (blank in listOf(false, true)) {
            val f = Fixture()
            f.result = "original complete transcript"
            f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
            val original = f.vm.state.value.transcript
            val history = f.vm.state.value.transcriptHistory
            val copies = f.copies.size
            val response = CompletableDeferred<TranscriptionResult>()
            coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
                thirdArg<((String) -> Unit)?>()?.invoke("retry partial")
                response.await()
            }
            f.vm.resendLastRecording(); runCurrent()
            assertEquals("retry partial", f.vm.state.value.transcript)
            if (blank) response.complete(TranscriptionResult("  ", "synthetic"))
            else response.completeExceptionally(IllegalStateException("synthetic failure"))
            runCurrent()
            assertEquals(original, f.vm.state.value.transcript)
            assertSame(history, f.vm.state.value.transcriptHistory)
            assertEquals(copies, f.copies.size)
            f.vm.toggleTranscriptMode()
            coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } returns TranscriptionResult("corrected", "synthetic")
            f.vm.resendLastRecording(); runCurrent()
            assertEquals("corrected", f.vm.state.value.transcript)
        }
    }

    @Test fun `consecutive replace rescues retain first rollback document despite stale completion`() = runTest(dispatcher) {
        val f = Fixture()
        f.result = "original complete transcript"
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val history = f.vm.state.value.transcriptHistory
        val copies = f.copies.size
        val oldResponse = CompletableDeferred<TranscriptionResult>()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            thirdArg<((String) -> Unit)?>()?.invoke("first partial")
            withContext(NonCancellable) { oldResponse.await() }
        }
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("first partial", f.vm.state.value.transcript)
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } coAnswers {
            thirdArg<((String) -> Unit)?>()?.invoke("second partial")
            yield()
            throw IllegalStateException("synthetic failure")
        }
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("original complete transcript", f.vm.state.value.transcript)
        assertSame(history, f.vm.state.value.transcriptHistory)
        assertEquals(copies, f.copies.size)
        f.vm.toggleTranscriptMode()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } returns TranscriptionResult("corrected", "synthetic")
        f.vm.resendLastRecording(); runCurrent()
        val current = f.vm.state.value
        oldResponse.complete(TranscriptionResult("stale", "synthetic")); runCurrent()
        assertEquals(current, f.vm.state.value)
        assertEquals("corrected", f.vm.state.value.transcript)
    }

    @Test fun `normal stop and recording rescue hold only the local WAV registration gate`() = runTest(dispatcher) {
        for (rescue in listOf(false, true)) {
            val f = Fixture()
            f.appendBase()
            f.vm.startRecording(); runCurrent()
            val releaseStop = CompletableDeferred<Unit>()
            val releaseCancel = CompletableDeferred<Unit>()
            var stopCalls = 0
            coEvery { f.mic.stop() } coAnswers {
                if (++stopCalls > 1) return@coAnswers null
                releaseStop.await()
                f.wav
            }
            coEvery { f.session.cancel() } coAnswers { releaseCancel.await() }
            if (rescue) f.vm.resendLastRecording() else f.vm.stopRecording()
            runCurrent()
            assertTrue(f.vm.state.value.localStopInProgress)
            assertFalse(f.vm.state.value.canResendRecording)
            f.vm.handleAppStopped() // Must not invalidate/cancel the local stop owner.
            f.vm.handleAppStarted()
            f.vm.resendLastRecording()
            f.vm.stopRecording()
            f.vm.startRecording()
            runCurrent()
            assertEquals(1, stopCalls)
            releaseStop.complete(Unit); runCurrent()
            assertFalse(f.vm.state.value.localStopInProgress)
            assertTrue(f.vm.state.value.hasRecordingFile)
            assertTrue(f.vm.state.value.canSaveRecording)
            assertTrue(f.vm.state.value.canResendRecording)
            f.result = "rescued after registration"
            f.vm.resendLastRecording(); runCurrent()
            assertEquals("base😀\nrescued after registration", f.vm.state.value.transcript)
            releaseCancel.complete(Unit); runCurrent()
            assertEquals("base😀\nrescued after registration", f.vm.state.value.transcript)
        }
    }

    @Test fun `failed or empty local stops release the gate for every stop entry`() = runTest(dispatcher) {
        for (entry in listOf("normal", "background", "rescue")) {
            for (throws in listOf(false, true)) {
                val f = Fixture()
                f.appendBase()
                f.vm.startRecording(); runCurrent()
                coEvery { f.mic.stop() } coAnswers {
                    if (throws) throw IllegalStateException("synthetic failure")
                    null
                }
                when (entry) {
                    "normal" -> f.vm.stopRecording()
                    "background" -> f.vm.handleAppStopped()
                    else -> f.vm.resendLastRecording()
                }
                runCurrent()
                assertFalse(f.vm.state.value.localStopInProgress)
                assertFalse(f.vm.state.value.hasRecordingFile)
                assertFalse(f.vm.state.value.canResendRecording)
                assertTrue(f.vm.state.value.canStartRecording)
                assertEquals("base😀", f.vm.state.value.transcript)
                f.vm.handleAppStarted()
                f.vm.startRecording(); runCurrent()
                assertEquals(RecordingStatus.Recording, f.vm.state.value.recordingStatus)
                f.vm.handleAppStopped(); runCurrent()
            }
        }
    }

    @Test fun `pending stop never falls back to an older WAV and registers only the new WAV`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent(); f.vm.stopRecording(); runCurrent()
        val original = f.vm.state.value.transcript
        f.vm.startRecording(); runCurrent()
        assertFalse(f.vm.state.value.hasRecordingFile)
        val newWav = files.newFile().apply { writeText("new synthetic audio") }
        val releaseStop = CompletableDeferred<Unit>()
        var stopCalls = 0
        coEvery { f.mic.stop() } coAnswers {
            if (++stopCalls > 1) return@coAnswers null
            releaseStop.await()
            newWav
        }
        f.vm.handleAppStopped(); runCurrent()
        f.vm.handleAppStarted()
        f.vm.resendLastRecording(); runCurrent()
        coVerify(exactly = 0) { f.client.transcribe(any<java.io.File>(), any(), any()) }
        releaseStop.complete(Unit); runCurrent()
        coEvery { f.client.transcribe(any<java.io.File>(), any(), any()) } answers {
            assertEquals(newWav, firstArg<java.io.File>())
            TranscriptionResult("new recording recovered", "synthetic")
        }
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("$original\nnew recording recovered", f.vm.state.value.transcript)
        assertEquals(1, stopCalls)
    }

    @Test fun `background request during a local stop defers cleanup until WAV registration`() = runTest(dispatcher) {
        val f = Fixture()
        f.appendBase()
        f.vm.startRecording(); runCurrent()
        val releaseStop = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { f.mic.stop() } coAnswers {
            if (++calls > 1) return@coAnswers null
            releaseStop.await()
            f.wav
        }
        coEvery { f.session.cancel() } coAnswers { releaseCancel.await() }
        f.vm.stopRecording(); runCurrent()
        f.vm.handleAppStopped()
        assertTrue(f.vm.state.value.localStopInProgress)
        releaseStop.complete(Unit); runCurrent()
        assertFalse(f.vm.state.value.localStopInProgress)
        assertTrue(f.vm.state.value.canSaveRecording)
        assertTrue(f.vm.state.value.canResendRecording)
        coVerify(exactly = 0) { f.session.commitAndStop(any()) }
        f.vm.handleAppStarted()
        f.result = "recovered"
        f.vm.resendLastRecording(); runCurrent()
        assertEquals("base😀\nrecovered", f.vm.state.value.transcript)
        assertEquals(1, calls)
        releaseCancel.complete(Unit); runCurrent()
        assertEquals("base😀\nrecovered", f.vm.state.value.transcript)
    }
}
