package com.yage.voiceflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yage.voiceflow.R

/**
 * Single source of truth for VoiceFlow's visual language: amber accent on
 * deep ink at night and warm paper-white during the day. Direct port of the
 * iOS DesignTokens.swift — hex values, typography sizes/weights, spacing, and
 * sizing all match the iOS spec.
 *
 * The light/dark color tokens are exposed as `@Composable` property getters
 * that resolve against [isSystemInDarkTheme] at read time, so call sites stay
 * a plain `DesignTokens.Palette.textSecondary` (no `.resolve()` needed) and
 * still flip with the system Dark/Light setting. They must therefore be read
 * inside a composable scope, which every consumer (components, screens) is.
 *
 * For the rare non-composable consumer (e.g. a Canvas that has already read
 * the dark flag once), [hex] / [darkHex] pairs are also reachable through
 * [resolveColor]. The accent is identical in both schemes (matches iOS), so it
 * is a plain constant.
 */
object DesignTokens {

    object Palette {
        // Light/dark hex pairs (sRGB, no alpha) ported from iOS DynamicColor.
        private const val BG_PRIMARY_LIGHT = 0xFAFAF7L
        private const val BG_PRIMARY_DARK = 0x0A0A0BL
        private const val BG_SECONDARY_LIGHT = 0xF2F2EEL
        private const val BG_SECONDARY_DARK = 0x141416L
        private const val TEXT_PRIMARY_LIGHT = 0x1A1A1AL
        private const val TEXT_PRIMARY_DARK = 0xF4F4F5L
        private const val TEXT_SECONDARY_LIGHT = 0x71717AL
        private const val TEXT_SECONDARY_DARK = 0xA1A1AAL
        private const val TEXT_TERTIARY_LIGHT = 0xA1A1AAL
        private const val TEXT_TERTIARY_DARK = 0x52525BL
        private const val DIVIDER_LIGHT = 0xE4E4E1L
        private const val DIVIDER_DARK = 0x27272AL

        val bgPrimary: Color
            @Composable @ReadOnlyComposable get() = pick(BG_PRIMARY_LIGHT, BG_PRIMARY_DARK)
        val bgSecondary: Color
            @Composable @ReadOnlyComposable get() = pick(BG_SECONDARY_LIGHT, BG_SECONDARY_DARK)
        val textPrimary: Color
            @Composable @ReadOnlyComposable get() = pick(TEXT_PRIMARY_LIGHT, TEXT_PRIMARY_DARK)
        val textSecondary: Color
            @Composable @ReadOnlyComposable get() = pick(TEXT_SECONDARY_LIGHT, TEXT_SECONDARY_DARK)
        val textTertiary: Color
            @Composable @ReadOnlyComposable get() = pick(TEXT_TERTIARY_LIGHT, TEXT_TERTIARY_DARK)
        val divider: Color
            @Composable @ReadOnlyComposable get() = pick(DIVIDER_LIGHT, DIVIDER_DARK)

        /** Amber accent — identical in light and dark, matching iOS. */
        val accent = Color(0xFFF0A868)
        val accentMuted = Color(0x33F0A868) // 0xF0A868 at 0.2 alpha
        /** Text/icon color drawn on top of the accent fill. */
        val onAccent = Color.Black

        @Composable
        @ReadOnlyComposable
        private fun pick(lightHex: Long, darkHex: Long): Color =
            resolveColor(lightHex, darkHex, isSystemInDarkTheme())

        /** Non-composable resolver for callers that already know the dark flag. */
        fun resolveColor(lightHex: Long, darkHex: Long, isDark: Boolean): Color =
            Color(0xFF000000L or (if (isDark) darkHex else lightHex))
    }

    /** Text styles from iOS Typography (design: .default == platform sans). */
    object Typography {
        val timer = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Thin)
        val title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium)
        val body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal)
        val bodyBold = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
        val caption = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
        val captionSub = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
        val buttonLabel = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }

    /**
     * Pixelate design language: the Silkscreen bitmap face (OFL) renders
     * pixel-discipline numerals and Latin labels — the timer, English status
     * captions, and STOP/Record button labels. CJK glyphs are absent from
     * Silkscreen, so Compose silently falls back to the system face for any
     * Chinese run; that fallback is intentional, not a defect.
     *
     * [Pixel] is a plain `FontFamily` constant — `R.font.*` references resolve
     * lazily at draw time, so no composable scope is needed here, and these
     * styles can be read from any call site exactly like [Typography].
     */
    object Pixel {
        val family = FontFamily(
            Font(R.font.silkscreen, FontWeight.Normal),
            Font(R.font.silkscreen_bold, FontWeight.Bold),
        )

        // Silkscreen has a large optical baseline, so sizes run a touch smaller
        // than their sans counterparts (timer 56 → 48) to occupy similar space.
        val timer = TextStyle(fontFamily = family, fontSize = 48.sp, fontWeight = FontWeight.Normal)
        val caption = TextStyle(fontFamily = family, fontSize = 14.sp, fontWeight = FontWeight.Normal)
        val button = TextStyle(fontFamily = family, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }

    object Spacing {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp
        val xxl = 48.dp
    }

    object Sizing {
        val buttonHeight = 56.dp
        val ghostButton = 36.dp
        val ghostIcon = 18.dp
        val waveformHeight = 80.dp
    }
}
