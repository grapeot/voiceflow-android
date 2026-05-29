package com.yage.voiceflow.model

/**
 * Port of the iOS `AppState.StreamCaptionKey` constants. The two-layer stream
 * caption (persistent + transient 3s flash) shown during recovery resolves to
 * these localized string keys.
 */
object StreamCaptionKey {
    const val RECONNECTING = "record.status.reconnecting"
    const val RECONNECTED = "record.status.reconnected"
    const val STREAM_DISCONNECTED = "record.error.streamDisconnected"
}
