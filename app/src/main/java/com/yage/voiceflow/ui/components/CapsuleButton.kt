package com.yage.voiceflow.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.voiceflow.ui.theme.DesignTokens

/**
 * Pill-shaped button with intrinsic width so localized labels (en/zh) never
 * truncate. Faithful port of iOS `CapsuleButton.swift`. Three roles:
 *
 * - [CapsuleButtonRole.Primary]: amber fill, black label — the one CTA.
 * - [CapsuleButtonRole.Secondary]: outlined amber border + amber label.
 * - [CapsuleButtonRole.Ghost]: text-only, no fill, no border.
 *
 * 56dp tall, disabled => opacity 0.4, pressed => opacity 0.85.
 *
 * The corners are a smooth capsule ([CircleShape]), matching iOS's final
 * button look. The leading glyph remains a hand-laid pixel mic/stop
 * ([CapsuleButtonIcon]).
 */
enum class CapsuleButtonRole { Primary, Secondary, Ghost }

/** Which hand-drawn pixel glyph the button shows on its leading edge. */
enum class CapsuleButtonIcon { None, Mic, Stop }

@Composable
fun CapsuleButton(
    title: String,
    role: CapsuleButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pixelIcon: CapsuleButtonIcon = CapsuleButtonIcon.None,
    isEnabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val foreground: Color = when (role) {
        CapsuleButtonRole.Primary -> DesignTokens.Palette.onAccent
        CapsuleButtonRole.Secondary -> DesignTokens.Palette.accent
        CapsuleButtonRole.Ghost -> DesignTokens.Palette.textSecondary
    }

    // Smooth capsule corners, matching iOS's final button look.
    val shape = CircleShape

    // Disabled => 0.4 (matches iOS `.opacity(isEnabled ? 1.0 : 0.4)`);
    // pressed => 0.85 (CapsulePressStyle); otherwise fully opaque.
    val contentAlpha = when {
        !isEnabled -> 0.4f
        isPressed -> 0.85f
        else -> 1.0f
    }

    val decoration: Modifier = when (role) {
        CapsuleButtonRole.Primary ->
            Modifier.background(DesignTokens.Palette.accent, shape)
        CapsuleButtonRole.Secondary ->
            Modifier.border(BorderStroke(1.5.dp, DesignTokens.Palette.accent), shape)
        CapsuleButtonRole.Ghost -> Modifier
    }

    Row(
        modifier = modifier
            .clip(shape)
            .alpha(contentAlpha)
            .then(decoration)
            // Intrinsic width with a sane minimum so a short label stays a pill.
            .defaultMinSize(minWidth = DesignTokens.Sizing.buttonHeight)
            .height(DesignTokens.Sizing.buttonHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = isEnabled,
                onClick = onClick,
            )
            .padding(horizontal = DesignTokens.Spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.s, Alignment.CenterHorizontally),
    ) {
        when (pixelIcon) {
            CapsuleButtonIcon.Mic -> PixelMicGlyph(color = foreground, size = 16.dp)
            CapsuleButtonIcon.Stop -> PixelStopGlyph(color = foreground, size = 16.dp)
            CapsuleButtonIcon.None -> Unit
        }
        Text(
            text = title,
            // Pixel hint pulled back: STOP/Record labels return to the regular
            // button face instead of Silkscreen.
            style = DesignTokens.Typography.buttonLabel,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}
