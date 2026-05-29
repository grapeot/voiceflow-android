package com.yage.voiceflow.model

/**
 * Port of the iOS `AppLanguage` enum. This is the UI *display* language ONLY —
 * it is never injected into the transcription prompt (see bug #1). It selects
 * which localized resource bundle the in-app language switch resolves strings
 * through (see `i18n/AppLocale.kt`, bug #2).
 *
 * [rawValue] is the persisted string (kept identical to the iOS raw values so
 * the semantics line up). [titleKey] is the localized label key shown in the
 * Settings language picker. [localeTag] is the BCP-47 tag fed to
 * `Locale.forLanguageTag`; null means "follow the system default".
 */
enum class AppLanguage(
    val rawValue: String,
    val titleKey: String,
    val localeTag: String?,
) {
    System("system", "settings.language.system", null),
    English("english", "settings.language.english", "en"),
    SimplifiedChinese("simplifiedChinese", "settings.language.simplifiedChinese", "zh-CN");

    companion object {
        /** Resolve a persisted raw value back to an enum, defaulting to [System]. */
        fun fromRawValue(raw: String?): AppLanguage =
            entries.firstOrNull { it.rawValue == raw } ?: System
    }
}
