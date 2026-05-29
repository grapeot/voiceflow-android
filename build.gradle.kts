// Root build file for the standalone VoiceFlowKit Android library project.
// Plugins are declared here with `apply false` and applied in the library module.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
