package com.yage.voiceflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * VoiceFlow's Material3 theme. The color scheme is seeded from
 * [DesignTokens]: the amber accent (#F0A868) is the Material `primary`, and
 * the background/surface/onX roles map to the iOS Palette light/dark variants
 * so stock Material components (NavigationBar, AlertDialog, TextField, etc.)
 * pick up the same warm-paper / deep-ink look as the hand-drawn components.
 *
 * Components that need exact iOS values (waveform, capsule button, status
 * text) read [DesignTokens] directly rather than through MaterialTheme; this
 * scheme only feeds the stock Material widgets.
 */
@Composable
fun VoiceFlowTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = DesignTokens.Palette

    // Resolve the iOS palette for the active scheme. The Palette getters are
    // @Composable and read isSystemInDarkTheme(), so they already yield the
    // correct light/dark variant here.
    val bgPrimary = p.bgPrimary
    val bgSecondary = p.bgSecondary
    val textPrimary = p.textPrimary
    val textSecondary = p.textSecondary
    val divider = p.divider

    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colorScheme = base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        secondary = p.accent,
        onSecondary = p.onAccent,
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgPrimary,
        onSurface = textPrimary,
        surfaceVariant = bgSecondary,
        onSurfaceVariant = textSecondary,
        outline = divider,
        outlineVariant = divider,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
