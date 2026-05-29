package com.yage.voiceflowkit

/**
 * Module marker. The real public API lives in
 * [VoiceFlowClient], [VoiceFlowSession], [VoiceFlowMicrophone],
 * [VoiceFlowConfig], [VoiceFlowError], and [StreamCaption].
 *
 * Port of the Swift `VoiceFlowKit` enum, which served the same role as a
 * namespace + version marker for the Swift package.
 */
object VoiceFlowKit {
    /** Library version. Kept in lockstep with the Swift package. */
    const val VERSION: String = "0.1.0-dev"
}
