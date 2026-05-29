package com.yage.voiceflow.model

import java.io.File

/**
 * Port of the iOS `SavedRecordingInfo` struct.
 *
 * Describes a recording the user explicitly saved to app storage. On iOS the
 * second field is a file URL; on Android it is the [File] in the app's files
 * directory.
 */
data class SavedRecordingInfo(
    val fileName: String,
    val file: File,
)
