package com.yage.voiceflowkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.yage.voiceflowkit.internal.AndroidAudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Public microphone capture facade. Wraps the internal
 * [AndroidAudioRecorder], streams PCM16 / 24 kHz / mono chunks through
 * `onPCMChunk`, exposes a smoothed [audioLevel], and can optionally
 * persist the recorded PCM to a WAV file (used by VoiceFlow's resend
 * feature). This is the Android analog of the Swift `VoiceFlowMicrophone`.
 *
 * ## Permission
 * A library cannot request runtime permission — only an `Activity` /
 * `ComponentActivity` can launch the system prompt. The host app must
 * declare and request `android.permission.RECORD_AUDIO` (the library's
 * manifest already merges in the `<uses-permission>` so hosts inherit
 * the declaration, but the *runtime grant* is the host's job).
 *
 * Use [hasPermission] to gate the UI, and request the grant via
 * `ActivityResultContracts.RequestPermission` in your Activity before
 * calling [start]. [requestPermission] exists for API parity with the
 * Swift facade but can only report the *current* grant state; it never
 * shows a prompt.
 *
 * @param context any [Context]; the application context is used for the
 *   permission check and for the WAV cache directory, so passing an
 *   `Activity` will not leak it.
 */
class VoiceFlowMicrophone(
    context: Context,
) {
    private val appContext: Context = context.applicationContext
    private val recorder = AndroidAudioRecorder(appContext)

    /**
     * Scope that owns the capture read loop. Recreated on each [start]
     * and cancelled on [stop] / [discard] so a new session always gets a
     * clean loop.
     */
    private var captureScope: CoroutineScope? = null

    /** EMA state for the level meter (30% new sample / 70% carried). */
    private var smoothedLevel = 0f

    private val _audioLevel = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    /**
     * 0..1 smoothed audio level for driving a waveform. Hot
     * [kotlinx.coroutines.flow.SharedFlow] with `replay = 1`, so a late
     * collector immediately sees the most recent level. Values are
     * EMA-smoothed (`current * 0.7 + raw * 0.3`) over the per-chunk RMS
     * level from [VoiceFlowAudioMetering.normalizedLevel], matching the
     * Swift `LevelSmoother`.
     */
    val audioLevel: Flow<Float> = _audioLevel.asSharedFlow()

    /**
     * The persisted WAV file, set after [stop] when capture was started
     * with `persist = true`; otherwise null. Mirrors Swift
     * `recordingFileURL`.
     */
    var recordingFile: File? = null
        private set

    /**
     * Whether `RECORD_AUDIO` is currently granted to the host app.
     * Call this before [start].
     */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the current `RECORD_AUDIO` grant state. Provided for parity
     * with the Swift `requestPermission()` API, but a library cannot show
     * the system permission prompt — this is equivalent to [hasPermission].
     * Request the grant from your Activity via
     * `ActivityResultContracts.RequestPermission`.
     */
    suspend fun requestPermission(): Boolean = hasPermission()

    /**
     * Start capturing with the default OpenAI realtime strategy.
     * Prefer [start] with an explicit [VoiceFlowRecordingStrategy] when the
     * host supports dual strategies.
     */
    suspend fun start(
        persist: Boolean = false,
        onPCMChunk: (ByteArray) -> Unit,
    ) {
        start(
            strategy = VoiceFlowRecordingStrategy.OPENAI_REALTIME,
            persist = persist,
            onPCMChunk = onPCMChunk,
        )
    }

    /**
     * Start capturing for the selected complete recording strategy.
     *
     * Both strategies currently capture PCM16 / 24 kHz / mono for metering and
     * local persistence. OpenAI hosts still stream chunks live; Grok hosts must
     * not open a realtime session and only upload after [stop].
     *
     * Android V0 of Grok Batch persists WAV (Grok STT accepts WAV). AAC-LC M4A
     * parity with iOS can follow without changing this public API.
     *
     * @throws VoiceFlowError.MicrophoneUnavailable if permission is not
     *   granted or the audio hardware fails to initialize.
     */
    suspend fun start(
        strategy: VoiceFlowRecordingStrategy,
        persist: Boolean = true,
        onPCMChunk: ((ByteArray) -> Unit)? = null,
    ) {
        if (!hasPermission()) {
            throw VoiceFlowError.MicrophoneUnavailable
        }

        recordingFile = null
        smoothedLevel = 0f
        // Grok always needs a local file for the post-Stop upload.
        val shouldPersist = persist || strategy == VoiceFlowRecordingStrategy.GROK_BATCH

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        captureScope = scope

        try {
            recorder.start(
                persist = shouldPersist,
                scope = scope,
                onChunk = { chunk ->
                    val raw = VoiceFlowAudioMetering.normalizedLevel(chunk)
                    smoothedLevel = smoothedLevel * 0.7f + raw * 0.3f
                    _audioLevel.tryEmit(smoothedLevel)
                    onPCMChunk?.invoke(chunk)
                },
                onError = {
                    scope.cancel()
                },
            )
        } catch (_: Throwable) {
            captureScope = null
            throw VoiceFlowError.MicrophoneUnavailable
        }
    }

    /**
     * Stop capturing. Returns the persisted WAV file if [start] recorded
     * one (`persist = true` with non-empty audio); otherwise null. The
     * underlying recorder is always released. Stopping with no active
     * recording is benign and returns null, matching Swift.
     */
    suspend fun stop(): File? {
        captureScope?.cancel()
        captureScope = null
        val file = recorder.stop()
        recordingFile = file
        return file
    }

    /**
     * Discard any in-progress recording without persisting. Idempotent.
     */
    fun discard() {
        captureScope?.cancel()
        captureScope = null
        recorder.discard()
        recordingFile = null
    }
}
