package com.yage.voiceflow.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Locale-aware string resolution (BUG #2 FIX).
 *
 * Use these instead of `androidx.compose.ui.res.stringResource`: they resolve
 * through [LocalAppLocale] (the context whose locale the in-app language picker
 * controls) rather than `LocalContext`. Because they read the
 * [LocalAppLocale] CompositionLocal, every call recomposes when the language
 * changes, so all visible UI text re-localizes instantly.
 */
@Composable
@ReadOnlyComposable
fun stringRes(@StringRes id: Int): String =
    LocalAppLocale.current.resources.getString(id)

@Composable
@ReadOnlyComposable
fun stringRes(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalAppLocale.current.resources.getString(id, *formatArgs)

/**
 * Resolve a string key (the iOS dotted key, e.g. `record.status.idle`) to its
 * Android string resource value through the active app locale. The Android
 * resource name is the iOS key with `.` replaced by `_`. Returns the key itself
 * if no matching resource exists (defensive; should not happen for ported keys).
 */
@Composable
@ReadOnlyComposable
fun stringResByKey(key: String): String {
    val context = LocalAppLocale.current
    val resName = key.replace('.', '_')
    val resId = context.resources.getIdentifier(resName, "string", context.packageName)
    return if (resId != 0) context.resources.getString(resId) else key
}

@Composable
@ReadOnlyComposable
fun stringResByKey(key: String, vararg formatArgs: Any): String {
    val context = LocalAppLocale.current
    val resName = key.replace('.', '_')
    val resId = context.resources.getIdentifier(resName, "string", context.packageName)
    return if (resId != 0) context.resources.getString(resId, *formatArgs) else key
}
