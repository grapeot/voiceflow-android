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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
 * Pixelate: the corners are NOT a smooth capsule. They are cut as a 2–3 step
 * square staircase ([PixelRoundedShape]) so the button reads as a pixel-art
 * rounded rectangle (old-game button corners), and the leading glyph is a hand
 * laid pixel mic/stop ([CapsuleButtonIcon]) instead of a smooth Material vector.
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

    // Pixelate: corners are a square staircase, not a smooth capsule. Steps are
    // sized in dp and resolved against the live density inside createOutline.
    val shape = PixelRoundedShape

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
            // Pixelate: STOP/Record labels render in Silkscreen; a localized CJK
            // label falls back to the system face automatically.
            style = DesignTokens.Pixel.button,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * A rounded-rectangle [Shape] whose corners are cut as a square staircase rather
 * than a smooth arc — the "pixel rounded corner" look. Each corner removes a few
 * step-sized squares so the silhouette steps in like an old-game button. Edges
 * stay perfectly straight; only the four corners are stepped.
 */
private val PixelRoundedShape = object : Shape {
    // 3 steps of 4dp each => a 12dp corner inset, readable as discrete blocks at
    // the 56dp button height without eating the label.
    private val stepCount = 3
    private val stepDp = 4f

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val step = with(density) { stepDp.dp.toPx() }
        val w = size.width
        val h = size.height
        // Guard against tiny sizes: never let the staircase exceed half a side.
        val maxStepsW = (w / 2f / step).toInt()
        val maxStepsH = (h / 2f / step).toInt()
        val n = minOf(stepCount, maxStepsW, maxStepsH).coerceAtLeast(0)

        if (n == 0) {
            val rect = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
            return Outline.Generic(rect)
        }

        val inset = n * step
        val path = Path().apply {
            // Start at the top edge, just right of the top-left staircase.
            moveTo(inset, 0f)
            lineTo(w - inset, 0f)
            // Top-right corner: step down-right.
            staircase(this, startX = w - inset, startY = 0f, n = n, step = step, dx = 1f, dy = 1f, horizontalFirst = true)
            lineTo(w, h - inset)
            // Bottom-right corner: step down-left.
            staircase(this, startX = w, startY = h - inset, n = n, step = step, dx = -1f, dy = 1f, horizontalFirst = false)
            lineTo(inset, h)
            // Bottom-left corner: step up-left.
            staircase(this, startX = inset, startY = h, n = n, step = step, dx = -1f, dy = -1f, horizontalFirst = true)
            lineTo(0f, inset)
            // Top-left corner: step up-right.
            staircase(this, startX = 0f, startY = inset, n = n, step = step, dx = 1f, dy = -1f, horizontalFirst = false)
            close()
        }
        return Outline.Generic(path)
    }

    /**
     * Append [n] right-angle steps to [path] from the given start point. Each
     * step is one [step]-sized square move: alternating a horizontal and a
     * vertical segment, producing the square-cut corner. [dx]/[dy] are ±1 to
     * pick the corner's direction; [horizontalFirst] picks which leg leads.
     */
    private fun staircase(
        path: Path,
        startX: Float,
        startY: Float,
        n: Int,
        step: Float,
        dx: Float,
        dy: Float,
        horizontalFirst: Boolean,
    ) {
        var x = startX
        var y = startY
        for (i in 0 until n) {
            if (horizontalFirst) {
                x += dx * step
                path.lineTo(x, y)
                y += dy * step
                path.lineTo(x, y)
            } else {
                y += dy * step
                path.lineTo(x, y)
                x += dx * step
                path.lineTo(x, y)
            }
        }
    }
}
