package com.yage.voiceflow.model

/** In-memory workflow choice; a new app session always starts in Replace. */
enum class TranscriptMode { Replace, Append }

/** Only the current recording's chunk is replaceable; the document base is frozen. */
internal class TranscriptComposition {
    var base = ""
        private set
    var inFlight = false
        private set
    private var lastChunkLength: Int? = null
    private var audioIsResendTarget = false
    private var beforeResend: String? = null
    private var beforeResendLength: Int? = null
    private var editedDuringFlight = false

    val locksEditor: Boolean get() = inFlight && base.isNotEmpty()

    fun begin(document: String, mode: TranscriptMode): String {
        base = if (mode == TranscriptMode.Append) document else ""
        inFlight = true
        audioIsResendTarget = false
        beforeResend = null
        beforeResendLength = null
        editedDuringFlight = false
        return base
    }

    /** Called only when this recording, rather than an older one, is the saved WAV. */
    fun audioPersisted() {
        audioIsResendTarget = true
        lastChunkLength = null
        beforeResend?.let {
            beforeResendLength = if (editedDuringFlight) null else visibleChunk(it).length.takeIf { length -> length > 0 }
        }
    }

    fun compose(chunk: String): String = compose(base, chunk)

    fun finish(chunk: String): String {
        val result = compose(chunk)
        lastChunkLength = chunk.length
        resetFlight()
        return result
    }

    fun beginResend(document: String, mode: TranscriptMode) {
        // Superseding a stuck resend must not discard its original rollback point.
        if (beforeResend == null) {
            beforeResendLength = if (inFlight) {
                val visible = visibleChunk(document)
                when {
                    editedDuringFlight -> null
                    visible.isEmpty() -> lastChunkLength
                    audioIsResendTarget -> visible.length
                    else -> null
                }
            } else lastChunkLength
            beforeResend = document
        }
        if (!inFlight) {
            base = if (mode == TranscriptMode.Append) {
                lastChunkLength?.takeIf { it <= document.length }?.let { document.dropLast(it) } ?: document
            } else ""
            audioIsResendTarget = true
        }
        inFlight = true
    }

    /** Failure/background: keep visible partials, or restore the pre-resend document. */
    fun settle(document: String): String {
        if (!inFlight) return document
        val restored = beforeResend
        if (restored != null) {
            lastChunkLength = beforeResendLength
            resetFlight()
            return restored
        }
        val visible = visibleChunk(document)
        if (visible.isNotEmpty()) lastChunkLength = if (audioIsResendTarget && !editedDuringFlight) visible.length else null
        resetFlight()
        return document
    }

    fun invalidateBoundary() {
        lastChunkLength = null
        if (inFlight) editedDuringFlight = true
    }

    fun visibleChunk(document: String): String {
        if (!document.startsWith(base)) return ""
        val tail = document.substring(base.length)
        return if (base.isNotEmpty() && !base.endsWith("\n") && tail.startsWith("\n")) tail.drop(1) else tail
    }

    private fun resetFlight() {
        base = ""
        inFlight = false
        audioIsResendTarget = false
        beforeResend = null
        beforeResendLength = null
        editedDuringFlight = false
    }

    companion object {
        fun compose(base: String, chunk: String): String = when {
            chunk.isEmpty() -> base
            base.isEmpty() || base.endsWith("\n") -> base + chunk
            else -> "$base\n$chunk"
        }
    }
}
