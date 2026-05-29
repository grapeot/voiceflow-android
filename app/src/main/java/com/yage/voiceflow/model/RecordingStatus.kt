package com.yage.voiceflow.model

/**
 * Recording lifecycle state. Mirrors the implicit state machine the iOS
 * `AppState` drives via `recordingStatus`-equivalent fields.
 *
 * [statusKey] maps to the `record.status.*` localized string keys shown in the
 * StatusText line. (Reconnecting / reconnected captions are handled separately
 * via the two-layer stream-caption mechanism, not this enum.)
 */
enum class RecordingStatus(val statusKey: String) {
    Idle("record.status.idle"),
    RequestingPermission("record.status.requestingPermission"),
    Recording("record.status.recording"),
    Transcribing("record.status.transcribing"),
    Ready("record.status.ready"),
}
