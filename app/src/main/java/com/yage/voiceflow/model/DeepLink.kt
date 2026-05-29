package com.yage.voiceflow.model

import android.net.Uri

/**
 * Port of the iOS `DeepLink` enum. Parses `voiceflow://record` (and the
 * scheme-only-host-missing variant `voiceflow:record`) into a start-recording
 * action.
 */
enum class DeepLinkAction {
    StartRecording,
}

object DeepLink {
    const val SCHEME = "voiceflow"
    const val RECORD_HOST = "record"

    fun parse(uri: Uri?): DeepLinkAction? {
        if (uri == null) return null
        if (uri.scheme?.lowercase() != SCHEME) return null

        val host = uri.host?.lowercase()
        val path = (uri.path ?: "").trim('/').lowercase()

        if (host == RECORD_HOST && path.isEmpty()) {
            return DeepLinkAction.StartRecording
        }
        if (host.isNullOrEmpty() && path == RECORD_HOST) {
            return DeepLinkAction.StartRecording
        }
        return null
    }
}
