package com.yage.voiceflow

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yage.voiceflow.data.SettingsStore
import com.yage.voiceflow.model.AppLanguage
import com.yage.voiceflow.model.ConnectionTestStatus
import com.yage.voiceflow.model.OpenCodeSendStatus
import com.yage.voiceflow.model.RecordingStatus
import com.yage.voiceflow.model.RecordingTimerFormatter
import com.yage.voiceflow.model.SavedRecordingInfo
import com.yage.voiceflow.model.StreamCaptionKey
import com.yage.voiceflow.model.TranscriptHistory
import com.yage.voiceflow.service.OpenCodeClient
import com.yage.voiceflow.service.OpenCodeClientError
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import com.yage.voiceflowkit.VoiceFlowConnectionPhase
import com.yage.voiceflowkit.VoiceFlowError
import com.yage.voiceflowkit.VoiceFlowEvent
import com.yage.voiceflowkit.VoiceFlowMicrophone
import com.yage.voiceflowkit.VoiceFlowSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reconcile a newly streamed transcript snapshot against the currently displayed
 * text, for the stop->finalize typewriter. The backend emits a growing series of
 * snapshots during finalize; this collapses each snapshot into the next text to
 * show, with append-only semantics where possible:
 *
 *  - identical content -> returns the SAME [current] instance, so the consumer can
 *    cheaply skip a recomposition (verified with assertSame in the unit test);
 *  - [incoming] is a forward growth of [current] (current is a prefix) -> returns
 *    [incoming] (append);
 *  - [current] is empty -> returns [incoming];
 *  - otherwise the backend re-segmented earlier text (incoming is not a prefix
 *    superset) -> returns [incoming] (replace).
 *
 * Kept top-level and framework-free so it can be unit tested directly.
 */
fun applyStreamedTranscript(current: String, incoming: String): String {
    if (incoming == current) return current
    if (current.isEmpty()) return incoming
    // Forward append (incoming extends current) and the re-segmentation replace
    // case both resolve to showing the incoming snapshot.
    return incoming
}

/**
 * All UI state surfaced to Compose, kept as one immutable snapshot updated via
 * `copy {}`. Direct port of the publishable surface of the iOS `AppState`
 * (+ all `AppState+*.swift` extensions).
 *
 * The one-shot record error is deliberately NOT a field here — it lives in a
 * separate [MainViewModel.recordErrorKey] StateFlow so that a tab switch
 * recomposing the dialog host cannot re-raise an already-acknowledged error
 * (bug #3). Everything else the views bind to is here.
 */
data class UiState(
    // --- Recording lifecycle ---
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val streamConnectionPhase: VoiceFlowConnectionPhase = VoiceFlowConnectionPhase.Disconnected,
    val recordingTimerText: String = "00:00",
    /** Smoothed 0..1 mic level from the kit (already EMA-smoothed; passed through). */
    val audioLevel: Float = 0f,

    // --- Transcript + history ---
    val transcript: String = "",
    val transcriptHistory: TranscriptHistory = TranscriptHistory(),

    // --- Two-layer stream captions ---
    val persistentStreamCaptionKey: String? = null,
    val transientStreamCaptionKey: String? = null,

    // --- Clipboard / save ---
    val lastClipboardStatusKey: String? = null,
    val lastSavedRecording: SavedRecordingInfo? = null,
    val shouldPresentSavedRecordingAlert: Boolean = false,
    /**
     * Whether a persisted WAV from the most recent capture exists on disk. Kept
     * in the immutable state (rather than read off the VM's private File) so the
     * derived [canSaveRecording] / [canResendRecording] gates recompose when it
     * changes. Set true after a successful stop, cleared when a new session
     * starts or the session is cancelled with no usable audio.
     */
    val hasRecordingFile: Boolean = false,

    // --- AI Builder token ---
    val hasToken: Boolean = false,
    val tokenDisplay: String = "",
    val connectionStatus: ConnectionTestStatus = ConnectionTestStatus.Untested,

    // --- OpenCode ---
    val openCodeServerURL: String = OpenCodeClient.DEFAULT_SERVER_URL,
    val openCodeUsername: String = OpenCodeClient.DEFAULT_USERNAME,
    val hasSavedOpenCodePassword: Boolean = false,
    val openCodePasswordDisplay: String = "",
    val openCodeConnectionStatus: ConnectionTestStatus = ConnectionTestStatus.Untested,
    val openCodeSendStatus: OpenCodeSendStatus = OpenCodeSendStatus.Idle,

    // --- Transcription settings ---
    val prompt: String = "",
    val terms: String = "",

    // --- Language ---
    val language: AppLanguage = AppLanguage.System,
) {
    /** Transient layer wins; once it clears, the persistent layer shows through. */
    val streamStatusCaptionKey: String?
        get() = transientStreamCaptionKey ?: persistentStreamCaptionKey

    // --- Derived gating (ports of the AppState computed `can*` properties) ---

    val canCopyTranscript: Boolean
        get() = transcript.trim().isNotEmpty()

    val canStartRecording: Boolean
        get() = recordingStatus == RecordingStatus.Idle || recordingStatus == RecordingStatus.Ready

    val canStopRecording: Boolean
        get() = recordingStatus == RecordingStatus.Recording

    /** Navigation (and save/resend) are only allowed while not mid-capture. */
    val canNavigateTranscriptHistory: Boolean
        get() = recordingStatus == RecordingStatus.Idle || recordingStatus == RecordingStatus.Ready

    val canNavigatePreviousTranscript: Boolean
        get() = canNavigateTranscriptHistory && transcriptHistory.hasPrevious

    val canNavigateNextTranscript: Boolean
        get() = canNavigateTranscriptHistory && transcriptHistory.hasNext

    val isOpenCodeConfigured: Boolean
        get() = hasSavedOpenCodePassword &&
            openCodeServerURL.trim().isNotEmpty() &&
            openCodeUsername.trim().isNotEmpty()

    val canSendToOpenCode: Boolean
        get() = canCopyTranscript &&
            isOpenCodeConfigured &&
            openCodeConnectionStatus == ConnectionTestStatus.Success

    /** Save is allowed while navigable and a persisted capture exists. */
    val canSaveRecording: Boolean
        get() = canNavigateTranscriptHistory && hasRecordingFile

    /** Resend can also force-stop an active recording and replay its persisted WAV. */
    val canResendRecording: Boolean
        get() = hasToken && (recordingStatus == RecordingStatus.Recording || canSaveRecording)

    /** Flat filename for the save-confirmation dialog (avoids dotted access). */
    val lastSavedRecordingFileName: String?
        get() = lastSavedRecording?.fileName
}

/**
 * Single source of truth for the app. Owns the [VoiceFlowClient],
 * [VoiceFlowMicrophone] (persisting WAV for save/resend), [OpenCodeClient], and
 * [SettingsStore]. Drives the full recording lifecycle, transcript history,
 * stream-recovery captions, clipboard, save/resend, OpenCode send/test, AI
 * Builder save/clear/test, and the elapsed timer.
 *
 * Faithful port of iOS `AppState` + every `AppState+*.swift` extension:
 *  - LiveSession: start/finish/cancel session, event consumer, bulk fallback.
 *  - StreamCaption: two-layer persistent + transient(3s) captions.
 *  - RecordingTimer: 1s elapsed timer.
 *  - TranscriptHistory: copy + prev/next navigation.
 *  - RecordingFiles: save current recording + confirmation.
 *  - OpenCode: password save/clear, test, send.
 *  - AIBuilderToken: save/clear, test.
 *
 * Permission is NOT requested here — a library/ViewModel cannot prompt. The
 * Activity owns the RECORD_AUDIO launcher and supplies an `ensureMicPermission`
 * suspend callback via [setMicPermissionRequester]; [startRecording] invokes it
 * (mirroring iOS `audioRecorder.requestPermission()`).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore.create(application)
    private val voiceFlowClient = VoiceFlowClient(settings.buildConfig())
    private val microphone = VoiceFlowMicrophone(application)
    private val openCodeClient = OpenCodeClient()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * One-shot record error key, SEPARATE from [UiState] (bug #3). The dialog is
     * shown iff this is non-null; acknowledging it calls [dismissRecordError]
     * which clears it exactly once. Navigation never sets it, so switching tabs
     * cannot re-raise an acknowledged error.
     */
    private val _recordErrorKey = MutableStateFlow<String?>(null)
    val recordErrorKey: StateFlow<String?> = _recordErrorKey.asStateFlow()

    // --- Live session machinery (ports the AppState stored vars) ---

    private var session: VoiceFlowSession? = null
    private var eventJob: Job? = null
    private var heartbeatJob: Job? = null
    private var levelJob: Job? = null
    private var timerJob: Job? = null
    private var transientCaptionJob: Job? = null

    /**
     * Stop->finalize typewriter pipeline. During finalize the kit calls back once
     * per delta (from several Dispatchers.IO coroutines), but pushing each snapshot
     * straight into the conflating [_state] StateFlow lets Compose drop the
     * intermediate values, so the whole transcript appears at once. Instead each
     * finalize snapshot is funneled through a non-conflating UNLIMITED channel and
     * drained by a single Main-thread consumer that applies one frame per tick,
     * producing a real per-delta typewriter. The channel + job are created fresh on
     * every stop and torn down deterministically.
     */
    private var finalizeTranscriptChannel: Channel<String>? = null
    private var finalizeTypewriterJob: Job? = null

    /** The persisted WAV of the most recent capture; drives save/resend. */
    private var lastRecordingFile: File? = null

    /** Throttle state for stream-mode clipboard writes (port of iOS fields). */
    private var lastStreamClipboardHash: Int? = null
    private var lastStreamClipboardUpdateMs: Long? = null

    /** True while the user has hand-edited the transcript mid-stream. */
    private var userEditedTranscriptDuringStream = false

    /** True during finalize teardown; suppresses recovery-failed noise. */
    private var isTranscriptionTeardown = false

    private var pendingDeepLinkStartRecording = false

    /**
     * Suspend callback supplied by the Activity (which owns the RECORD_AUDIO
     * launcher) to prompt for the microphone grant and await the user's choice.
     * The kit/ViewModel cannot show the system prompt themselves; [startRecording]
     * invokes this exactly where iOS calls `audioRecorder.requestPermission()`.
     * Returns true if the grant is (or becomes) available.
     */
    private var micPermissionRequester: (suspend () -> Boolean)? = null

    /** Wire the Activity's RECORD_AUDIO requester into the lifecycle. */
    fun setMicPermissionRequester(requester: suspend () -> Boolean) {
        micPermissionRequester = requester
    }

    /** Fixed, read-only AI Builder endpoint shown in Settings (matches iOS). */
    val aiBuilderEndpoint: String = VoiceFlowConfig.DEFAULT_ENDPOINT

    init {
        refreshSettingsState()
        observeAudioLevel()
    }

    // region Settings mirror

    private fun refreshSettingsState() {
        _state.update {
            it.copy(
                hasToken = settings.hasToken(),
                tokenDisplay = settings.tokenDisplay(),
                openCodeServerURL = settings.openCodeServerURL,
                openCodeUsername = settings.openCodeUsername,
                hasSavedOpenCodePassword = settings.hasOpenCodePassword(),
                openCodePasswordDisplay = settings.openCodePasswordDisplay(),
                prompt = settings.prompt,
                terms = settings.termsRaw,
                language = settings.language,
            )
        }
    }

    // endregion

    // region AI Builder token (port of AppState+AIBuilderToken.swift)

    fun saveAIBuilderToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        if (settings.saveToken(trimmed)) {
            _state.update {
                it.copy(
                    hasToken = true,
                    tokenDisplay = settings.tokenDisplay(),
                    connectionStatus = ConnectionTestStatus.Untested,
                )
            }
        } else {
            _state.update {
                it.copy(connectionStatus = ConnectionTestStatus.Failed("settings.connection.saveFailed", null))
            }
        }
    }

    fun clearAIBuilderToken() {
        if (!settings.clearToken()) {
            _state.update {
                it.copy(connectionStatus = ConnectionTestStatus.Failed("settings.connection.clearFailed", null))
            }
            return
        }
        _state.update {
            it.copy(
                hasToken = false,
                tokenDisplay = settings.tokenDisplay(),
                connectionStatus = ConnectionTestStatus.Untested,
            )
        }
    }

    fun testAIBuilderConnection() {
        val token = settings.getToken()
        if (token.isEmpty()) {
            _state.update {
                it.copy(connectionStatus = ConnectionTestStatus.Failed("settings.connection.missingToken", null))
            }
            return
        }
        _state.update { it.copy(connectionStatus = ConnectionTestStatus.Testing) }
        viewModelScope.launch {
            try {
                voiceFlowClient.updateConfig(settings.buildConfig())
                voiceFlowClient.testConnection()
                _state.update { it.copy(connectionStatus = ConnectionTestStatus.Success) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionTestStatus.Failed(
                            "settings.connection.failed",
                            userFacingErrorDetail(t),
                        ),
                    )
                }
            }
        }
    }

    // endregion

    // region Transcription + language settings

    fun updatePrompt(value: String) {
        settings.prompt = value
        _state.update { it.copy(prompt = value) }
    }

    fun updateTerms(value: String) {
        settings.termsRaw = value
        _state.update { it.copy(terms = value) }
    }

    fun updateLanguage(language: AppLanguage) {
        settings.language = language
        _state.update { it.copy(language = language) }
    }

    // endregion

    // region OpenCode settings (port of AppState+OpenCode.swift)

    fun updateOpenCodeServerURL(value: String) {
        settings.openCodeServerURL = value
        _state.update {
            // Changing the URL invalidates a prior connection test (iOS didSet).
            val status = if (it.openCodeServerURL != value) {
                ConnectionTestStatus.Untested
            } else {
                it.openCodeConnectionStatus
            }
            it.copy(openCodeServerURL = value, openCodeConnectionStatus = status)
        }
    }

    fun updateOpenCodeUsername(value: String) {
        settings.openCodeUsername = value
        _state.update {
            val status = if (it.openCodeUsername != value) {
                ConnectionTestStatus.Untested
            } else {
                it.openCodeConnectionStatus
            }
            it.copy(openCodeUsername = value, openCodeConnectionStatus = status)
        }
    }

    fun saveOpenCodePassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) return
        if (settings.saveOpenCodePassword(trimmed)) {
            _state.update {
                it.copy(
                    hasSavedOpenCodePassword = true,
                    openCodePasswordDisplay = settings.openCodePasswordDisplay(),
                    openCodeSendStatus = OpenCodeSendStatus.Idle,
                    openCodeConnectionStatus = ConnectionTestStatus.Untested,
                )
            }
        } else {
            _state.update {
                it.copy(openCodeSendStatus = OpenCodeSendStatus.Failed("settings.openCode.saveFailed"))
            }
        }
    }

    fun clearOpenCodePassword() {
        if (!settings.clearOpenCodePassword()) {
            _state.update {
                it.copy(openCodeSendStatus = OpenCodeSendStatus.Failed("settings.openCode.clearFailed"))
            }
            return
        }
        _state.update {
            it.copy(
                hasSavedOpenCodePassword = false,
                openCodePasswordDisplay = settings.openCodePasswordDisplay(),
                openCodeSendStatus = OpenCodeSendStatus.Idle,
                openCodeConnectionStatus = ConnectionTestStatus.Untested,
            )
        }
    }

    fun testOpenCodeConnection() {
        val snapshot = _state.value
        val password = settings.getOpenCodePassword()
        if (!snapshot.isOpenCodeConfigured || password.isEmpty()) {
            _state.update {
                it.copy(
                    openCodeConnectionStatus = ConnectionTestStatus.Failed(
                        "settings.openCode.connection.missingConfig",
                        null,
                    ),
                )
            }
            return
        }
        _state.update { it.copy(openCodeConnectionStatus = ConnectionTestStatus.Testing) }
        viewModelScope.launch {
            try {
                openCodeClient.testConnection(
                    serverURL = snapshot.openCodeServerURL.trim(),
                    username = snapshot.openCodeUsername.trim(),
                    password = password,
                )
                _state.update { it.copy(openCodeConnectionStatus = ConnectionTestStatus.Success) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        openCodeConnectionStatus = ConnectionTestStatus.Failed(
                            "settings.openCode.connection.failed",
                            userFacingErrorDetail(t),
                        ),
                    )
                }
            }
        }
    }

    fun sendTranscriptToOpenCode() {
        val snapshot = _state.value
        if (!snapshot.canCopyTranscript) return
        val password = settings.getOpenCodePassword()
        if (!snapshot.isOpenCodeConfigured || password.isEmpty()) {
            _state.update {
                it.copy(openCodeSendStatus = OpenCodeSendStatus.Failed("record.openCode.error.notConfigured"))
            }
            return
        }
        if (snapshot.openCodeConnectionStatus != ConnectionTestStatus.Success) {
            _state.update {
                it.copy(openCodeSendStatus = OpenCodeSendStatus.Failed("record.openCode.error.connectionNotVerified"))
            }
            return
        }
        _state.update { it.copy(openCodeSendStatus = OpenCodeSendStatus.Sending) }
        viewModelScope.launch {
            try {
                openCodeClient.sendTranscript(
                    text = snapshot.transcript,
                    serverURL = snapshot.openCodeServerURL.trim(),
                    username = snapshot.openCodeUsername.trim(),
                    password = password,
                )
                _state.update { it.copy(openCodeSendStatus = OpenCodeSendStatus.Success) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(openCodeSendStatus = OpenCodeSendStatus.Failed("record.openCode.error.sendFailed"))
                }
            }
        }
    }

    // endregion

    // region Recording lifecycle (port of AppState.startRecording / stopRecording)

    private fun observeAudioLevel() {
        levelJob?.cancel()
        levelJob = viewModelScope.launch {
            // The kit already EMA-smooths (0.7/0.3) and emits via a replay=1
            // SharedFlow, matching iOS updateAudioLevel — we pass through, no
            // double smoothing.
            microphone.audioLevel.collect { level ->
                _state.update { it.copy(audioLevel = level) }
            }
        }
    }

    fun startRecording() {
        if (!_state.value.canStartRecording) return
        viewModelScope.launch { startRecordingInternal() }
    }

    private suspend fun startRecordingInternal() {
        // Token gate (mirrors the two iOS guards: hasToken + non-empty token).
        if (!settings.hasToken() || settings.getToken().isEmpty()) {
            presentRecordError("record.error.missingToken")
            return
        }

        _state.update { it.copy(recordingStatus = RecordingStatus.RequestingPermission) }

        // The Activity owns the runtime prompt. Ask it to ensure the grant and
        // await the user's choice (mirrors iOS `audioRecorder.requestPermission()`).
        // If no requester is wired, fall back to the kit's current-grant check so
        // a missing grant still surfaces as a readable error.
        val granted = micPermissionRequester?.invoke() ?: microphone.hasPermission()
        if (!granted || !microphone.hasPermission()) {
            presentRecordError("record.error.microphoneDenied")
            return
        }

        try {
            // Reset per-session state (mirrors the iOS do-block setup).
            _state.update {
                it.copy(
                    transcript = "",
                    lastClipboardStatusKey = null,
                    lastSavedRecording = null,
                    shouldPresentSavedRecordingAlert = false,
                    hasRecordingFile = false,
                    openCodeSendStatus = OpenCodeSendStatus.Idle,
                    streamConnectionPhase = VoiceFlowConnectionPhase.Connecting,
                    persistentStreamCaptionKey = null,
                    transientStreamCaptionKey = null,
                )
            }
            transientCaptionJob?.cancel()
            transientCaptionJob = null
            userEditedTranscriptDuringStream = false
            lastStreamClipboardHash = null
            lastStreamClipboardUpdateMs = null

            voiceFlowClient.updateConfig(settings.buildConfig())
            val newSession = voiceFlowClient.startSession()
            session = newSession
            // Start the event consumer immediately so the earliest PhaseChanged
            // is caught before mic.start (the SharedFlow has replay=0).
            startLiveEventConsumer(newSession)
            startStreamHeartbeat(newSession)

            microphone.start(persist = true) { chunk ->
                // Forward each PCM chunk; launched so the capture callback never
                // blocks. Audio level comes from microphone.audioLevel, so we do
                // not meter here (unlike iOS which taps the chunk for level).
                viewModelScope.launch { runCatching { newSession.sendAudioChunk(chunk) } }
            }

            resetRecordingTimer()
            startRecordingTimer()
            _state.update { it.copy(recordingStatus = RecordingStatus.Recording) }
        } catch (t: Throwable) {
            cancelLiveTranscriptionSession()
            resetRecordingTimer()
            presentRecordError("record.error.recordingFailed")
        }
    }

    fun stopRecording() {
        if (_state.value.recordingStatus != RecordingStatus.Recording) return
        stopRecordingTimer()
        _state.update { it.copy(recordingStatus = RecordingStatus.Transcribing) }
        viewModelScope.launch {
            val wav: File?
            try {
                wav = microphone.stop()
            } catch (t: Throwable) {
                cancelLiveTranscriptionSession()
                presentRecordError("record.error.transcriptionFailed")
                return@launch
            }

            // Empty / missing capture: treat exactly like the iOS byteCount==0 path.
            if (wav == null || !wav.exists() || wav.length() == 0L) {
                wav?.let { runCatching { it.delete() } }
                cancelLiveTranscriptionSession()
                presentRecordError("record.error.transcriptionFailed")
                return@launch
            }

            lastRecordingFile = wav
            _state.update { it.copy(hasRecordingFile = true) }
            finishLiveTranscriptionSession()
        }
    }

    /**
     * Finalize the live stream, fall back to bulk transcription on the saved WAV
     * if the stream text is unusable. Direct port of iOS
     * `finishLiveTranscriptionSession`.
     */
    private suspend fun finishLiveTranscriptionSession() {
        stopStreamHeartbeat()
        isTranscriptionTeardown = true
        // Start a fresh per-delta typewriter pipeline before any finalize callbacks
        // arrive (recording-time deltas are still suppressed by handleStreamEvent).
        startFinalizeTypewriter()
        try {
            val activeSession = session
            if (activeSession == null) {
                completeStopTranscriptionFailure()
                return
            }

            var streamText = ""
            try {
                streamText = activeSession.commitAndStop { partial ->
                    updateTranscriptDuringFinalize(partial)
                }
            } catch (_: Throwable) {
                // Swallow; the fallback path below handles unusable output.
            }

            cancelLiveTranscriptionSession()

            if (isUsableTranscript(streamText)) {
                // Let the typewriter drain every queued delta before the final
                // write, so the animation is never truncated by the overwrite.
                drainFinalizeTypewriter()
                completeStopTranscriptionSuccess(streamText)
                return
            }

            val bulk = finishTranscriptionFromLastRecording(presentErrorOnFailure = false)
            if (bulk != null && isUsableTranscript(bulk)) {
                drainFinalizeTypewriter()
                completeStopTranscriptionSuccess(bulk)
                return
            }

            completeStopTranscriptionFailure()
        } finally {
            isTranscriptionTeardown = false
        }
    }

    /**
     * Bulk transcription of the last persisted WAV. Used by the stop-recording
     * fallback and by [resendLastRecording]. Port of iOS
     * `finishTranscriptionFromLastRecording`.
     */
    private suspend fun finishTranscriptionFromLastRecording(presentErrorOnFailure: Boolean): String? {
        val file = lastRecordingFile
        if (file == null || !file.exists()) {
            if (presentErrorOnFailure) presentRecordError("record.error.transcriptionFailed")
            return null
        }
        if (settings.getToken().isEmpty()) {
            if (presentErrorOnFailure) presentRecordError("record.error.missingToken")
            return null
        }
        return try {
            voiceFlowClient.updateConfig(settings.buildConfig())
            val result = voiceFlowClient.transcribe(wavFile = file) { partial ->
                _state.update { it.copy(transcript = partial) }
            }
            result.text
        } catch (t: Throwable) {
            if (presentErrorOnFailure) presentRecordError("record.error.transcriptionFailed")
            null
        }
    }

    private fun isUsableTranscript(text: String): Boolean = text.trim().length > 3

    private fun completeStopTranscriptionSuccess(text: String) {
        stopFinalizeTypewriter()
        _recordErrorKey.value = null
        _state.update {
            it.copy(
                transcript = text,
                openCodeSendStatus = OpenCodeSendStatus.Idle,
                streamConnectionPhase = VoiceFlowConnectionPhase.Disconnected,
                persistentStreamCaptionKey = null,
                transientStreamCaptionKey = null,
                transcriptHistory = it.transcriptHistory.add(text),
                recordingStatus = RecordingStatus.Ready,
            )
        }
        transientCaptionJob?.cancel()
        transientCaptionJob = null
        copyTranscript()
    }

    private fun completeStopTranscriptionFailure() {
        stopFinalizeTypewriter()
        val current = _state.value.transcript
        if (isUsableTranscript(current)) {
            _state.update {
                it.copy(
                    transcriptHistory = it.transcriptHistory.add(current),
                    recordingStatus = RecordingStatus.Ready,
                    persistentStreamCaptionKey = StreamCaptionKey.STREAM_DISCONNECTED,
                )
            }
            copyTranscript()
            return
        }
        presentRecordError("record.error.transcriptionFailed")
    }

    fun resendLastRecording() {
        val snapshot = _state.value
        if (!settings.hasToken()) return
        if (snapshot.recordingStatus != RecordingStatus.Recording) {
            val file = lastRecordingFile
            if (!snapshot.canNavigateTranscriptHistory) return
            if (file == null || !file.exists()) return
        }

        _state.update {
            it.copy(
                recordingStatus = RecordingStatus.Transcribing,
                openCodeSendStatus = OpenCodeSendStatus.Idle,
                lastClipboardStatusKey = null,
            )
        }
        viewModelScope.launch {
            if (snapshot.recordingStatus == RecordingStatus.Recording) {
                val wav = try {
                    stopRecordingTimer()
                    microphone.stop()
                } catch (t: Throwable) {
                    cancelLiveTranscriptionSession()
                    presentRecordError("record.error.transcriptionFailed")
                    return@launch
                }

                if (wav == null || !wav.exists() || wav.length() == 0L) {
                    wav?.let { runCatching { it.delete() } }
                    cancelLiveTranscriptionSession()
                    presentRecordError("record.error.transcriptionFailed")
                    return@launch
                }

                lastRecordingFile = wav
                _state.update { it.copy(hasRecordingFile = true) }
                cancelLiveTranscriptionSession()
            }

            val bulk = finishTranscriptionFromLastRecording(presentErrorOnFailure = true)
            if (bulk != null) {
                _state.update {
                    it.copy(
                        transcript = bulk,
                        transcriptHistory = it.transcriptHistory.add(bulk),
                        recordingStatus = RecordingStatus.Ready,
                    )
                }
                copyTranscript()
            }
        }
    }

    /**
     * Stream-mode event handler. Port of iOS `handleStreamEvent`. The event
     * consumer marshals each event here on the main scope.
     */
    private fun handleStreamEvent(event: VoiceFlowEvent) {
        when (event) {
            is VoiceFlowEvent.PartialTranscript -> {
                // iOS ignores stream deltas while status is .recording (the live
                // transcript only updates during finalize / generating).
                if (_state.value.recordingStatus == RecordingStatus.Recording) return
                if (!userEditedTranscriptDuringStream) {
                    _state.update { it.copy(transcript = event.text) }
                    throttledStreamClipboardWrite(event.text)
                }
            }

            is VoiceFlowEvent.PhaseChanged -> {
                val phase = event.phase
                _state.update { it.copy(streamConnectionPhase = phase) }
                when (phase) {
                    VoiceFlowConnectionPhase.Connected,
                    VoiceFlowConnectionPhase.Connecting -> {
                        if (_state.value.recordingStatus == RecordingStatus.Recording &&
                            _state.value.persistentStreamCaptionKey == StreamCaptionKey.RECONNECTING
                        ) {
                            setPersistentStreamCaption(null)
                            flashTransientStreamCaption(StreamCaptionKey.RECONNECTED)
                        }
                    }

                    VoiceFlowConnectionPhase.Recovering -> {
                        if (_state.value.recordingStatus == RecordingStatus.Recording) {
                            setPersistentStreamCaption(StreamCaptionKey.RECONNECTING)
                        }
                    }

                    VoiceFlowConnectionPhase.Disconnected,
                    VoiceFlowConnectionPhase.Generating -> Unit
                }
            }

            is VoiceFlowEvent.RecoveryStarted -> {
                _state.update { it.copy(streamConnectionPhase = VoiceFlowConnectionPhase.Recovering) }
                if (_state.value.recordingStatus == RecordingStatus.Recording) {
                    setPersistentStreamCaption(StreamCaptionKey.RECONNECTING)
                }
            }

            is VoiceFlowEvent.RecoveryFailed -> {
                if (isTranscriptionTeardown) return
                _state.update { it.copy(streamConnectionPhase = VoiceFlowConnectionPhase.Disconnected) }
                val status = _state.value.recordingStatus
                val hasTranscript = _state.value.transcript.trim().isNotEmpty()
                when {
                    status == RecordingStatus.Recording ->
                        setPersistentStreamCaption(StreamCaptionKey.STREAM_DISCONNECTED)
                    status == RecordingStatus.Transcribing ->
                        setPersistentStreamCaption(StreamCaptionKey.STREAM_DISCONNECTED)
                    hasTranscript ->
                        setPersistentStreamCaption(StreamCaptionKey.STREAM_DISCONNECTED)
                    else ->
                        presentRecordError("record.error.transcriptionFailed")
                }
            }
        }
    }

    private fun updateTranscriptDuringFinalize(partial: String) {
        // Route the delta through the non-conflating typewriter channel so Compose
        // sees every snapshot in order. trySend always succeeds on an UNLIMITED
        // channel; if no pipeline is active (defensive), fall back to a direct
        // state write so a snapshot is never silently dropped.
        val channel = finalizeTranscriptChannel
        if (channel == null || channel.trySend(partial).isSuccess.not()) {
            _state.update { it.copy(transcript = partial) }
        }
        throttledStreamClipboardWrite(partial)
    }

    /**
     * Start a fresh stop->finalize typewriter pipeline: tear down any prior one,
     * open a new non-conflating UNLIMITED channel (a CONFLATED channel would drop
     * intermediate deltas and reintroduce the one-shot bug), and launch a single
     * Main-thread consumer that drains it one frame per tick via
     * [applyStreamedTranscript]. Each `stop` calls this so every session begins
     * with a clean pipeline.
     */
    private fun startFinalizeTypewriter() {
        teardownFinalizeTypewriter()
        val channel = Channel<String>(Channel.UNLIMITED)
        finalizeTranscriptChannel = channel
        finalizeTypewriterJob = viewModelScope.launch(Dispatchers.Main) {
            for (snapshot in channel) {
                _state.update { current ->
                    val next = applyStreamedTranscript(current.transcript, snapshot)
                    if (next == current.transcript) current else current.copy(transcript = next)
                }
                delay(FINALIZE_TYPEWRITER_FRAME_MS)
            }
        }
    }

    /**
     * Gracefully drain the typewriter before writing the final text: close the
     * channel so the consumer finishes every already-queued snapshot, then join
     * (NOT cancel) so the animation is not truncated mid-flight. Safe to call when
     * no pipeline is active.
     */
    private suspend fun drainFinalizeTypewriter() {
        finalizeTranscriptChannel?.close()
        finalizeTranscriptChannel = null
        finalizeTypewriterJob?.join()
        finalizeTypewriterJob = null
    }

    /** Defensive teardown: cancel the consumer and drop the channel immediately. */
    private fun stopFinalizeTypewriter() {
        teardownFinalizeTypewriter()
    }

    private fun teardownFinalizeTypewriter() {
        finalizeTranscriptChannel?.close()
        finalizeTranscriptChannel = null
        finalizeTypewriterJob?.cancel()
        finalizeTypewriterJob = null
    }

    private fun startLiveEventConsumer(session: VoiceFlowSession) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            session.events.collect { event -> handleStreamEvent(event) }
        }
    }

    private fun startStreamHeartbeat(session: VoiceFlowSession) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                delay(STREAM_HEARTBEAT_INTERVAL_MS)
                runCatching { session.ping() }
            }
        }
    }

    private fun stopStreamHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun cancelLiveTranscriptionSession() {
        stopStreamHeartbeat()
        eventJob?.cancel()
        eventJob = null
        session?.let { runCatching { it.cancel() } }
        session = null
        _state.update {
            it.copy(
                streamConnectionPhase = VoiceFlowConnectionPhase.Disconnected,
                persistentStreamCaptionKey = null,
                transientStreamCaptionKey = null,
                audioLevel = 0f,
            )
        }
        transientCaptionJob?.cancel()
        transientCaptionJob = null
    }

    // endregion

    // region Stream captions (port of AppState+StreamCaption.swift)

    private fun setPersistentStreamCaption(key: String?) {
        _state.update { it.copy(persistentStreamCaptionKey = key) }
    }

    private fun flashTransientStreamCaption(key: String) {
        transientCaptionJob?.cancel()
        _state.update { it.copy(transientStreamCaptionKey = key) }
        transientCaptionJob = viewModelScope.launch {
            delay(TRANSIENT_CAPTION_DURATION_MS)
            _state.update { it.copy(transientStreamCaptionKey = null) }
        }
    }

    /**
     * Throttled stream-mode clipboard write. Port of iOS
     * `throttledStreamClipboardWrite`: skip short text, dedupe by hash, and
     * rate-limit to once per second.
     */
    private fun throttledStreamClipboardWrite(text: String) {
        val trimmed = text.trim()
        if (trimmed.length <= 3) return

        val hash = trimmed.hashCode()
        val now = System.currentTimeMillis()
        val lastUpdate = lastStreamClipboardUpdateMs
        if (hash == lastStreamClipboardHash && lastUpdate != null && now - lastUpdate < 1_000) {
            return
        }
        lastStreamClipboardHash = hash
        lastStreamClipboardUpdateMs = now
        val ok = writeToClipboard(trimmed)
        _state.update {
            it.copy(lastClipboardStatusKey = if (ok) "record.clipboard.copied" else "record.clipboard.failed")
        }
    }

    // endregion

    // region Recording timer (port of AppState+RecordingTimer.swift)

    private fun resetRecordingTimer() {
        stopRecordingTimer()
        _state.update { it.copy(recordingTimerText = RecordingTimerFormatter.format(0)) }
    }

    private fun startRecordingTimer() {
        timerJob?.cancel()
        val startMs = System.currentTimeMillis()
        _state.update { it.copy(recordingTimerText = RecordingTimerFormatter.format(0)) }
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val elapsed = ((System.currentTimeMillis() - startMs) / 1_000).toInt()
                _state.update { it.copy(recordingTimerText = RecordingTimerFormatter.format(elapsed)) }
            }
        }
    }

    private fun stopRecordingTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // endregion

    // region Transcript clipboard + history (port of AppState+TranscriptHistory.swift)

    /**
     * User edits to the editable transcript field. Sets the
     * `userEditedTranscriptDuringStream` flag (port of the iOS field) so live
     * stream deltas don't clobber a hand-edit, then writes the new text.
     */
    fun onTranscriptEdited(value: String) {
        userEditedTranscriptDuringStream = true
        _state.update { it.copy(transcript = value) }
    }

    fun copyTranscript() {
        val text = _state.value.transcript
        if (text.trim().isEmpty()) return
        val ok = writeToClipboard(text)
        _state.update {
            it.copy(lastClipboardStatusKey = if (ok) "record.clipboard.copied" else "record.clipboard.failed")
        }
    }

    fun navigatePreviousTranscript() {
        if (!_state.value.canNavigatePreviousTranscript) return
        val result = _state.value.transcriptHistory.navigatePrevious()
        val text = result.text ?: return
        _state.update {
            it.copy(
                transcriptHistory = result.history,
                transcript = text,
                openCodeSendStatus = OpenCodeSendStatus.Idle,
                lastClipboardStatusKey = null,
            )
        }
    }

    fun navigateNextTranscript() {
        if (!_state.value.canNavigateNextTranscript) return
        val result = _state.value.transcriptHistory.navigateNext()
        val text = result.text ?: return
        _state.update {
            it.copy(
                transcriptHistory = result.history,
                transcript = text,
                openCodeSendStatus = OpenCodeSendStatus.Idle,
                lastClipboardStatusKey = null,
            )
        }
    }

    // endregion

    // region Save recording (port of AppState+RecordingFiles.swift)

    fun saveCurrentRecording() {
        val source = lastRecordingFile
        if (!_state.value.canSaveRecording || source == null || !source.exists()) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val dir = File(getApplication<Application>().getExternalFilesDir(null), "VoiceFlow")
                    if (!dir.exists()) dir.mkdirs()
                    val fileName = "voiceflow-${System.currentTimeMillis()}.wav"
                    val dest = File(dir, fileName)
                    source.copyTo(dest, overwrite = true)
                    SavedRecordingInfo(fileName = fileName, file = dest)
                } catch (_: Throwable) {
                    null
                }
            }
            if (saved != null) {
                _state.update {
                    it.copy(
                        lastSavedRecording = saved,
                        shouldPresentSavedRecordingAlert = true,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        lastSavedRecording = null,
                        shouldPresentSavedRecordingAlert = false,
                        lastClipboardStatusKey = "record.save.failed",
                    )
                }
            }
        }
    }

    fun acknowledgeSavedRecordingAlert() {
        _state.update { it.copy(shouldPresentSavedRecordingAlert = false) }
    }

    // endregion

    // region One-shot record error (bug #3)

    /**
     * Set the one-shot error key, force the lifecycle back to Idle, and stop the
     * timer. Port of iOS `presentRecordError`. Setting an error never re-raises
     * on its own — it is consumed state cleared by [dismissRecordError].
     */
    private fun presentRecordError(key: String) {
        _recordErrorKey.value = key
        _state.update { it.copy(recordingStatus = RecordingStatus.Idle) }
        stopRecordingTimer()
    }

    /** Acknowledge / dismiss the error. Clears the key exactly once. */
    fun dismissRecordError() {
        _recordErrorKey.value = null
    }

    /**
     * Surface the microphone-denied one-shot error. Called by the Record screen
     * when the Activity's RECORD_AUDIO prompt is declined, mirroring the iOS
     * `record.error.microphoneDenied` path. Goes through [presentRecordError] so
     * it is the same consumed-state model as every other record error (bug #3).
     */
    fun presentMicrophoneDenied() {
        presentRecordError("record.error.microphoneDenied")
    }

    // endregion

    // region Deep link (port of AppState deep-link handling)

    fun handleDeepLinkStartRecording() {
        pendingDeepLinkStartRecording = true
        consumePendingDeepLinkStartRecordingIfNeeded()
    }

    private fun consumePendingDeepLinkStartRecordingIfNeeded() {
        if (!pendingDeepLinkStartRecording) return
        pendingDeepLinkStartRecording = false
        startRecording()
    }

    // endregion

    // region Clipboard + error detail helpers

    private fun writeToClipboard(text: String): Boolean = try {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VoiceFlow transcript", text))
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Human-readable failure detail for the connection-test status line. Mirrors
     * iOS `userFacingErrorDetail`: prefer a typed, localized description, then
     * fall back to the throwable's message / class name.
     */
    private fun userFacingErrorDetail(t: Throwable): String = when (t) {
        is VoiceFlowError.HttpError -> "HTTP ${t.statusCode}"
        is VoiceFlowError.ConnectionLost -> t.detail
        is VoiceFlowError.WebsocketError -> t.detail
        is VoiceFlowError.Underlying -> t.detail
        is OpenCodeClientError -> t.messageKey
        else -> t.message ?: t.toString()
    }

    // endregion

    override fun onCleared() {
        super.onCleared()
        stopStreamHeartbeat()
        eventJob?.cancel()
        eventJob = null
        timerJob?.cancel()
        transientCaptionJob?.cancel()
        levelJob?.cancel()
        teardownFinalizeTypewriter()
        // discard() releases the recorder and cancels the capture loop; the
        // session's socket is torn down by the kit once its scope is gone.
        microphone.discard()
        session = null
    }

    companion object {
        /** iOS uses a 12s WS heartbeat while recording. */
        private const val STREAM_HEARTBEAT_INTERVAL_MS = 12_000L

        /** iOS `transientStreamCaptionDuration` = 3s. */
        private const val TRANSIENT_CAPTION_DURATION_MS = 3_000L

        /** One typewriter frame per drained finalize delta (~83 fps cap). */
        private const val FINALIZE_TYPEWRITER_FRAME_MS = 12L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return MainViewModel(app) as T
            }
        }
    }
}
