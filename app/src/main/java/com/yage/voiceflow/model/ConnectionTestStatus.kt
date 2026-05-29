package com.yage.voiceflow.model

/**
 * Port of the iOS `ConnectionStatus` enum.
 *
 * Drives the connection-test status line in Settings for both the AI Builder
 * token and OpenCode. The localized string key differs between the two
 * sections (`settings.connection.*` vs `settings.openCode.connection.*`), so a
 * caller chooses the right resolver via [localizedKey] / [openCodeLocalizedKey].
 *
 * The [Failed] case carries an explicit `messageKey` (so a specific failure
 * such as "missing token" can override the generic "connection failed" key)
 * plus an optional human-readable [detail] line.
 */
sealed class ConnectionTestStatus {
    data object Untested : ConnectionTestStatus()
    data object Testing : ConnectionTestStatus()
    data object Success : ConnectionTestStatus()
    data class Failed(val messageKey: String, val detail: String? = null) : ConnectionTestStatus()

    /** String key for the AI Builder section status line. */
    val localizedKey: String
        get() = when (this) {
            Untested -> "settings.connection.untested"
            Testing -> "settings.connection.testing"
            Success -> "settings.connection.success"
            is Failed -> messageKey
        }

    /** String key for the OpenCode section status line. */
    val openCodeLocalizedKey: String
        get() = when (this) {
            Untested -> "settings.openCode.connection.untested"
            Testing -> "settings.openCode.connection.testing"
            Success -> "settings.openCode.connection.success"
            is Failed -> messageKey
        }

    val detailOrNull: String?
        get() = (this as? Failed)?.detail
}
