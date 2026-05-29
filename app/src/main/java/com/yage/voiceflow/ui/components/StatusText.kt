package com.yage.voiceflow.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.yage.voiceflow.ui.theme.DesignTokens

/**
 * Single line (up to two) of muted status text under the timer. Faithful port
 * of iOS `StatusText.swift`. Color pops to amber only while actively recording.
 *
 * - [StatusTextRole.Neutral]: text.secondary — most states.
 * - [StatusTextRole.Accent]: amber — recording in progress.
 * - [StatusTextRole.Muted]: text.tertiary — disconnected, idle hints.
 */
enum class StatusTextRole { Neutral, Accent, Muted }

@Composable
fun StatusText(
    text: String,
    role: StatusTextRole,
    modifier: Modifier = Modifier,
) {
    val color: Color = when (role) {
        StatusTextRole.Neutral -> DesignTokens.Palette.textSecondary
        StatusTextRole.Accent -> DesignTokens.Palette.accent
        StatusTextRole.Muted -> DesignTokens.Palette.textTertiary
    }

    Text(
        text = text,
        style = DesignTokens.Typography.caption,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}
