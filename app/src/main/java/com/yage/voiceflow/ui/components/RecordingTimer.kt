package com.yage.voiceflow.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.yage.voiceflow.ui.theme.DesignTokens

/**
 * The "MM:SS" recording timer header. Faithful port of the iOS timer label:
 * `DesignTokens.Typography.timer` (56sp, thin weight) with `.monospacedDigit()`
 * so the digits don't jitter as they tick. Rendered in text.primary.
 *
 * The formatted text is supplied by the ViewModel (RecordingTimerFormatter);
 * this composable owns only the typography.
 */
@Composable
fun RecordingTimer(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        // "tnum, lnum" == iOS `.monospacedDigit()`: tabular (equal-advance)
        // lining figures so the timer width never shifts between e.g.
        // "00:11" and "00:18".
        style = DesignTokens.Typography.timer.merge(
            TextStyle(fontFeatureSettings = "tnum, lnum"),
        ),
        color = DesignTokens.Palette.textPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
