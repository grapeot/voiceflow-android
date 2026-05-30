package com.yage.voiceflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pixelate bottom-nav glyphs. Each icon is hand-laid on a 7×7 logical grid and
 * stamped as square cells (no anti-aliased curves), so the mic and gear read as
 * deliberate pixel art rather than scaled-down Material vectors. Selected tabs
 * tint amber, unselected tabs the muted nav grey — colour is passed in by the
 * caller so it follows the same accent discipline as the rest of the screen.
 *
 * The grids are intentionally coarse (the whole point of the language is that
 * the pixel is visible); each "1" below is one filled cell.
 */

private const val GRID = 7

// 7×7 microphone: a 3-wide capsule head, a stem, and a base bar.
private val MIC_GRID = intArrayOf(
    0, 0, 1, 1, 1, 0, 0,
    0, 0, 1, 1, 1, 0, 0,
    0, 0, 1, 1, 1, 0, 0,
    0, 0, 1, 1, 1, 0, 0,
    1, 0, 0, 1, 0, 0, 1,
    0, 1, 0, 1, 0, 1, 0,
    0, 0, 1, 1, 1, 0, 0,
)

// 7×7 gear: a ring of teeth around a hollow centre.
private val GEAR_GRID = intArrayOf(
    0, 0, 1, 0, 1, 0, 0,
    0, 1, 1, 1, 1, 1, 0,
    1, 1, 0, 0, 0, 1, 1,
    0, 1, 0, 0, 0, 1, 0,
    1, 1, 0, 0, 0, 1, 1,
    0, 1, 1, 1, 1, 1, 0,
    0, 0, 1, 0, 1, 0, 0,
)

@Composable
fun PixelMicIcon(color: Color, modifier: Modifier = Modifier) {
    PixelGridIcon(MIC_GRID, color, modifier)
}

@Composable
fun PixelGearIcon(color: Color, modifier: Modifier = Modifier) {
    PixelGridIcon(GEAR_GRID, color, modifier)
}

@Composable
private fun PixelGridIcon(grid: IntArray, color: Color, modifier: Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        drawPixelGrid(grid, color)
    }
}

/**
 * Small reusable pixel mic glyph for in-button use. Reuses the exact same 7×7
 * [MIC_GRID] as the Record tab so the two readings stay identical; only the
 * drawn size differs (the record button wants ~15–18dp, the tab wants 24dp).
 * Colour follows the button foreground (black `onAccent` on the amber Primary).
 */
@Composable
fun PixelMicGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        drawPixelGrid(MIC_GRID, color)
    }
}

/**
 * Small reusable pixel stop glyph for the recording state of the Record button.
 * A single chunky 3×3 filled square laid on the same coarse grid so it reads as
 * the same pixel language as the mic — deliberately blocky, no rounding.
 */
@Composable
fun PixelStopGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        // A solid 3×3 block centred on the same 7×7 grid the mic uses, so the
        // stop and mic share one optical weight.
        val cell = this.size.minDimension / GRID
        val gap = cell * 0.12f
        val fill = cell - gap
        for (row in 2..4) {
            for (col in 2..4) {
                drawRect(
                    color = color,
                    topLeft = Offset(col * cell + gap / 2f, row * cell + gap / 2f),
                    size = Size(fill, fill),
                )
            }
        }
    }
}

private fun DrawScope.drawPixelGrid(grid: IntArray, color: Color) {
    // One cell per grid unit; leave a hairline gap between cells so neighbouring
    // pixels stay visually distinct (the gap sells the "pixel" reading).
    val cell = size.minDimension / GRID
    val gap = cell * 0.12f
    val fill = cell - gap
    for (row in 0 until GRID) {
        for (col in 0 until GRID) {
            if (grid[row * GRID + col] == 0) continue
            drawRect(
                color = color,
                topLeft = Offset(col * cell + gap / 2f, row * cell + gap / 2f),
                size = Size(fill, fill),
            )
        }
    }
}
