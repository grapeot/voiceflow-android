package com.yage.voiceflow.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yage.voiceflow.ui.theme.DesignTokens

/**
 * Tertiary icon-only action: history chevrons, the more-menu trigger, copy.
 * Faithful port of iOS `GhostIconButton.swift`. Stays at text.secondary weight
 * so it never competes with the waveform or the primary CTA.
 *
 * 36dp tap target / 18dp icon. Enabled => text.secondary; disabled =>
 * text.tertiary at 0.6 opacity.
 */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    contentDescription: String? = null,
) {
    val tint = if (isEnabled) {
        DesignTokens.Palette.textSecondary
    } else {
        DesignTokens.Palette.textTertiary
    }

    IconButton(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier
            .size(DesignTokens.Sizing.ghostButton)
            .clip(CircleShape)
            .alpha(if (isEnabled) 1.0f else 0.6f),
        // Don't let the framework's disabled tint override ours; we mirror iOS
        // exactly (tertiary + 0.6 alpha) instead of Material's default.
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = tint,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(DesignTokens.Sizing.ghostIcon),
        )
    }
}

/**
 * The "more" overflow trigger renders the same 36/18 ghost geometry but is not
 * itself a button — it anchors a DropdownMenu in the caller. Exposed as a Box
 * so RecordScreen can wrap it with `clickable` + the menu.
 */
@Composable
fun GhostIconGlyph(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier.size(DesignTokens.Sizing.ghostButton),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DesignTokens.Palette.textSecondary,
            modifier = Modifier.size(DesignTokens.Sizing.ghostIcon),
        )
    }
}
