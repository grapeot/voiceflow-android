package com.yage.voiceflowkit.internal

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * PCM capture engine. Port of the proven opencode
 * `AudioRecorderManager.startRealtimeCapture` path.
 *
 * Uses [AudioRecord] configured for 24 kHz / mono / PCM16, which is the
 * exact format the realtime backend expects, so no resampling is needed
 * for the live mic path. (Resampling only matters for file import, which
 * V0 does not do.) The read loop runs on [Dispatchers.IO] inside a
 * caller-supplied [CoroutineScope] and emits `copyOf(bytesRead)` chunks
 * to [onChunk].
 *
 * The Swift counterpart ([VoiceFlowMicrophone] over `AudioRecorder`) also
 * persists the captured PCM to a WAV so VoiceFlow can re-send the audio.
 * When [start]'s `persist` flag is set, we accumulate the raw PCM and
 * write a canonical 24 kHz mono WAV from [stop].
 *
 * This class is `internal`: hosts interact with it through the public
 * [com.yage.voiceflowkit.VoiceFlowMicrophone] facade.
 */
internal class AndroidAudioRecorder(
    private val context: Context,
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    /** Accumulated PCM, only allocated when `persist == true`. */
    private var pcmBuffer: ByteArrayOutputStream? = null

    val isRecording: Boolean
        get() = audioRecord != null

    /**
     * Start capturing PCM16 / 24 kHz / mono.
     *
     * @param persist when true, the raw PCM is buffered so [stop] can
     *   write a WAV file; when false, [stop] returns null.
     * @param scope the scope that owns the read loop. The mic facade
     *   supplies a scope it can cancel; the loop runs on [Dispatchers.IO].
     * @param onChunk invoked (suspending) with each captured chunk —
     *   `copyOf(bytesRead)`, so the buffer is safe to retain.
     * @param onError invoked once if the read loop fails while still the
     *   active recorder. Not called for a clean stop/discard.
     *
     * @throws IllegalStateException if a recording is already active, the
     *   reported min buffer size is invalid, or [AudioRecord] fails to
     *   initialize (the platform analog of Swift's `couldNotCreateRecorder`).
     */
    fun start(
        persist: Boolean,
        scope: CoroutineScope,
        onChunk: suspend (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (isRecording) {
            throw IllegalStateException("Recorder is already running")
        }

        val sampleRate = RealtimeTranscriptionConfig.SAMPLE_RATE
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("AudioRecord min buffer size is invalid: $minBufferSize")
        }

        // Match the proven reference: read buffer is max(minBuffer, 4096),
        // and the AudioRecord internal buffer is double that to absorb jitter.
        val readBufferSize = max(minBufferSize, PCM_READ_BUFFER_SIZE_BYTES)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            readBufferSize * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        pcmBuffer = if (persist) ByteArrayOutputStream() else null
        audioRecord = recorder
        recorder.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(readBufferSize)
            try {
                // `audioRecord === recorder` is the live-recorder guard the
                // proven reference uses: stop()/discard() null it out, which
                // cleanly ends the loop without an error callback.
                while (audioRecord === recorder) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    when {
                        bytesRead > 0 -> {
                            val chunk = buffer.copyOf(bytesRead)
                            pcmBuffer?.write(chunk)
                            onChunk(chunk)
                        }
                        bytesRead == 0 -> Unit
                        else -> throw IllegalStateException("AudioRecord read failed: $bytesRead")
                    }
                }
            } catch (error: Throwable) {
                if (audioRecord === recorder) {
                    onError(error)
                }
            }
        }
        Log.d(TAG, "Realtime PCM capture started: sampleRate=$sampleRate, buffer=$readBufferSize")
    }

    /**
     * Stop capturing and release the recorder.
     *
     * Returns a freshly written WAV file if capture was started with
     * `persist = true` and at least one sample was captured; otherwise
     * returns null. Mirrors Swift `stopRecording()` writing PCM via the
     * WAV writer.
     */
    fun stop(): File? {
        val recorder = audioRecord ?: return null
        audioRecord = null
        captureJob?.cancel()
        captureJob = null

        try {
            recorder.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to stop realtime PCM capture: ${error.message}")
        } finally {
            recorder.release()
            Log.d(TAG, "Realtime PCM capture stopped")
        }

        val pcm = pcmBuffer?.toByteArray()
        pcmBuffer = null
        if (pcm == null || pcm.isEmpty()) {
            return null
        }

        val outFile = File.createTempFile(WAV_FILE_PREFIX, WAV_FILE_SUFFIX, context.cacheDir)
        Pcm16WavWriter.writeWav(pcm, RealtimeTranscriptionConfig.SAMPLE_RATE, outFile)
        return outFile
    }

    /**
     * Drop any in-progress capture without persisting. Idempotent — safe
     * to call when nothing is recording.
     */
    fun discard() {
        val recorder = audioRecord
        audioRecord = null
        captureJob?.cancel()
        captureJob = null
        pcmBuffer = null
        if (recorder != null) {
            try {
                recorder.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to stop on discard: ${error.message}")
            } finally {
                recorder.release()
            }
        }
    }

    private companion object {
        private const val TAG = "AndroidAudioRecorder"
        private const val PCM_READ_BUFFER_SIZE_BYTES = 4_096
        private const val WAV_FILE_PREFIX = "voiceflow-recording-"
        private const val WAV_FILE_SUFFIX = ".wav"
    }
}
