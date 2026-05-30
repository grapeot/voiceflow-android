package com.yage.voiceflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.yage.voiceflow.ui.theme.DesignTokens
import kotlin.math.abs
import kotlin.math.max

/**
 * The single visual anchor of the Record screen. Faithful Compose Canvas port
 * of the iOS `WaveformView.swift`. Three modes:
 *
 * - [WaveformMode.Idle]: a faint horizontal hairline, no motion.
 * - [WaveformMode.Active]: a scrolling history of recent mic levels — each bar
 *   is the audio amplitude N×33ms ago. The newest sample enters on the right
 *   and ages off the left.
 * - [WaveformMode.Generating]: a traveling pulse that sweeps left to right,
 *   used while the backend is finalizing transcription (no mic signal).
 *
 * Geometry: 23 soft-cornered bars, 9dp wide, 6dp spacing, 80dp tall, centered
 * and vertically symmetric, each a single rounded rectangle (corner radius =
 * barWidth/2) — matching iOS's final waveform look. 23 bars (23*9 + 22*6 =
 * 339dp) fit inside the screen's content width without overflowing. The
 * three-mode animation logic is unchanged from the iOS port. Updates throttled
 * to ~30Hz.
 */
enum class WaveformMode { Idle, Active, Generating }

private const val BAR_COUNT = 23
private const val FRAME_INTERVAL_SECONDS = 1.0 / 30.0

@Composable
fun WaveformView(
    mode: WaveformMode,
    color: Color,
    level: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val barWidthDp = 9.dp
    val barSpacingDp = 6.dp

    // The animation loop below runs inside a LaunchedEffect(mode) that only
    // restarts when `mode` changes. A plain capture of `level` would freeze at
    // the value present when Active began (≈0), so the bars never react to the
    // live mic level. rememberUpdatedState keeps the loop reading the latest
    // emitted level without restarting the loop on every sample.
    val currentLevel by rememberUpdatedState(level)

    // Ring buffer of recent mic levels (newest on the right). Survives
    // recompositions / mode changes so leaving Active can decay gracefully.
    val history = remember { FloatArray(BAR_COUNT) }
    // Throttle guard, in seconds, mirroring the Swift `lastTick`.
    var lastTick by remember { mutableStateOf(0.0) }
    // Current animation clock in seconds (ports timeline.date.timeIntervalSinceReferenceDate).
    var frameTime by remember { mutableStateOf(0.0) }
    val startNanos = remember { mutableLongStateOf(0L) }

    // Drive the clock. While Idle the Swift TimelineView is paused; we mirror
    // that by only advancing `frameTime` (the redraw trigger) while there is
    // something to animate. Active/Generating animate every frame; Idle animates
    // only until the decay tail has collapsed, then goes fully static.
    LaunchedEffect(mode) {
        while (true) {
            val now = withFrameNanos { it }
            if (startNanos.longValue == 0L) startNanos.longValue = now
            val t = (now - startNanos.longValue) / 1_000_000_000.0

            when (mode) {
                WaveformMode.Active -> {
                    // Throttle to ~30Hz so 60Hz redraws don't burn through
                    // samples faster than the mic delivers them.
                    if (t - lastTick >= FRAME_INTERVAL_SECONDS) {
                        lastTick = t
                        // Subtle floor (~0.04) keeps the row visible during
                        // quiet pauses mid-sentence rather than collapsing flat.
                        val sample = max(0.04f, currentLevel)
                        // Shift left, drop oldest, append newest on the right.
                        System.arraycopy(history, 1, history, 0, BAR_COUNT - 1)
                        history[BAR_COUNT - 1] = sample
                    }
                    // Bars come from `history`; bump `frameTime` so the Canvas
                    // (which reads the non-observable FloatArray) recomposes.
                    frameTime = t
                }
                WaveformMode.Generating -> {
                    // Pulse is a pure function of `t`; redraw every frame.
                    frameTime = t
                }
                WaveformMode.Idle -> {
                    // Decay the bars when we leave Active so the visual collapses
                    // gracefully instead of snapping flat. Once the tail is gone,
                    // stop touching state so a static hairline isn't redrawn 60×/s.
                    if (history.any { it > 0.01f }) {
                        if (t - lastTick >= FRAME_INTERVAL_SECONDS) {
                            lastTick = t
                            for (i in history.indices) history[i] *= 0.6f
                            frameTime = t
                        }
                    }
                }
            }
        }
    }

    val canvasOpacity = if (mode == WaveformMode.Idle) 0.45f else 1.0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(DesignTokens.Sizing.waveformHeight),
    ) {
        val barWidthPx = barWidthDp.toPx()
        val barSpacingPx = barSpacingDp.toPx()
        drawBars(
            bars = currentBars(mode, frameTime, history),
            color = color.copy(alpha = color.alpha * canvasOpacity),
            barWidthPx = barWidthPx,
            barSpacingPx = barSpacingPx,
        )
    }
}

private fun DrawScope.drawBars(
    bars: FloatArray,
    color: Color,
    barWidthPx: Float,
    barSpacingPx: Float,
) {
    val centerY = size.height / 2f
    val total = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * barSpacingPx
    val originX = (size.width - total) / 2f

    // Each bar is one smooth, soft-cornered rectangle (corner radius =
    // barWidth/2), vertically centred and symmetric about the centre line —
    // matching iOS's final waveform look (no pixel-block stacking).
    val cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)

    for (i in 0 until BAR_COUNT) {
        val barX = originX + i * (barWidthPx + barSpacingPx)
        // height = level * (height - 4) + 2, clamped to at least 1px.
        val rawHeight = bars[i] * (size.height - 4f) + 2f
        val barHeight = max(rawHeight, 1f)

        drawRoundRect(
            color = color,
            topLeft = Offset(barX, centerY - barHeight / 2f),
            size = Size(barWidthPx, barHeight),
            cornerRadius = cornerRadius,
        )
    }
}

/** Bar heights (0…1) for the current frame. Direct port of `currentBars(at:)`. */
private fun currentBars(mode: WaveformMode, t: Double, history: FloatArray): FloatArray {
    return when (mode) {
        WaveformMode.Idle -> FloatArray(BAR_COUNT) { 0.02f }

        WaveformMode.Active -> history.copyOf()

        WaveformMode.Generating -> {
            // Traveling pulse — independent of mic level, indicates the server
            // is doing the work now.
            val speed = 12.0
            val position = (t * speed) % BAR_COUNT.toDouble()
            FloatArray(BAR_COUNT) { i ->
                val raw = abs(i.toDouble() - position)
                val distance = minOf(raw, BAR_COUNT.toDouble() - raw)
                // Narrower falloff (/1.5) so the pulse reads crisply across the
                // few wide blocks instead of smearing over the whole row.
                val intensity = max(0.0, 1.0 - distance / 1.5)
                (0.04 + intensity * 0.96).toFloat()
            }
        }
    }
}
