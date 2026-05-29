package com.yage.voiceflow.model

/**
 * Port of the iOS `OpenCodeSendStatus` enum.
 *
 * Reflects the state of the "Send to OpenCode" action so the Record overflow
 * menu item and its status line can show idle / sending / sent / failed.
 * [Failed] carries the localized string key to display (e.g.
 * `record.openCode.error.sendFailed`).
 */
sealed class OpenCodeSendStatus {
    data object Idle : OpenCodeSendStatus()
    data object Sending : OpenCodeSendStatus()
    data object Success : OpenCodeSendStatus()
    data class Failed(val messageKey: String) : OpenCodeSendStatus()

    val localizedKey: String
        get() = when (this) {
            Idle -> "record.openCode.idle"
            Sending -> "record.openCode.sending"
            Success -> "record.openCode.sent"
            is Failed -> messageKey
        }
}
