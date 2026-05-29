package com.yage.voiceflow.i18n

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.yage.voiceflow.model.AppLanguage
import java.util.Locale

/**
 * Real in-app UI language switch (BUG #2 FIX).
 *
 * Android's normal `stringResource()` resolves through `LocalContext`, whose
 * locale follows the *device* language. To switch the app's UI language at
 * runtime — without changing the device language and without pulling in
 * AppCompat / `recreate()` — we build a derived [Context] via
 * `createConfigurationContext` whose configuration locale is forced to the
 * chosen language, then expose it through [LocalAppLocale].
 *
 * `MainActivity` wraps `setContent` in
 * `CompositionLocalProvider(LocalAppLocale provides localizedContext(language))`.
 * When the language preference changes, the provided [Context] instance
 * changes, every `stringRes()` call (see `i18n/Strings.kt`) recomposes, and all
 * visible text flips immediately.
 *
 * For [AppLanguage.System] we return the base context unchanged so resource
 * resolution follows the device language exactly as the platform default.
 */
val LocalAppLocale = staticCompositionLocalOf<Context> {
    error("LocalAppLocale not provided — wrap content in CompositionLocalProvider(LocalAppLocale provides ...)")
}

/**
 * Build a [Context] whose resources resolve in the language selected by
 * [language]. [base] is typically the Activity (or application) context.
 */
fun localizedContext(base: Context, language: AppLanguage): Context {
    val tag = language.localeTag ?: return base
    val locale = Locale.forLanguageTag(tag)
    val config = android.content.res.Configuration(base.resources.configuration).apply {
        setLocale(locale)
    }
    return base.createConfigurationContext(config)
}
