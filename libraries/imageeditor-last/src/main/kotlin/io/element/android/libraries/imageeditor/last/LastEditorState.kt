/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import io.element.android.libraries.core.perf.trace

/**
 * State holder for the hybrid editor. Trace sections all live under `imageeditor.last.*` so the
 * dashboard cards group separately from baseline/modular/photoeditor.
 *
 * Difference from `ModularEditorState`: [makeDefaultBrush] takes a screen `density` and feeds
 * `Brush.size` in **pixels** (`widthDp × density`) — Jetpack Ink's `CanvasStrokeRenderer` treats
 * `Brush.size` as screen pixels when invoked with an identity matrix, so without the density
 * multiplier a "6 dp" stroke previously rendered as 6 px (≈ 2 dp) on xxhdpi devices, three
 * times thinner than the user picked. The `LastBitmapExporter` mirrors this by scaling stroke
 * width on bitmap by `brush.size × sx` (no extra density factor — that would double-count).
 */
@Stable
class LastEditorState internal constructor(
    val config: LastImageEditorConfig,
    private val density: Float,
) {
    var activeTool: LastEditorTool by mutableStateOf(LastEditorTool.None)
        internal set

    var canvasSizePx: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    var imageIntrinsicSize: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    // --- Rotate / flip ----------------------------------------------------

    var rotationDegrees: Int by mutableStateOf(0)
        internal set

    var flippedHorizontally: Boolean by mutableStateOf(false)
        internal set

    fun rotate90Clockwise() = trace("imageeditor.last.transform.rotate") {
        rotationDegrees = (rotationDegrees + 90) % 360
    }

    fun toggleHorizontalFlip() = trace("imageeditor.last.transform.flip") {
        flippedHorizontally = !flippedHorizontally
    }

    fun selectTool(tool: LastEditorTool) = trace("imageeditor.last.tool.select") {
        activeTool = tool
    }

    // --- Crop -------------------------------------------------------------

    var cropLeft: Float by mutableStateOf(0f); internal set
    var cropTop: Float by mutableStateOf(0f); internal set
    var cropRight: Float by mutableStateOf(1f); internal set
    var cropBottom: Float by mutableStateOf(1f); internal set

    val cropIsFull: Boolean
        get() = cropLeft == 0f && cropTop == 0f && cropRight == 1f && cropBottom == 1f

    fun resetCrop() = trace("imageeditor.last.crop.reset") {
        cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f
    }

    fun applyCropRect(l: Float, t: Float, r: Float, b: Float) = trace("imageeditor.last.crop.apply") {
        cropLeft = l; cropTop = t; cropRight = r; cropBottom = b
    }

    // --- Drawing ----------------------------------------------------------

    val inkStrokes: SnapshotStateList<Stroke> = mutableStateListOf()

    var drawColor: Color by mutableStateOf(config.drawingPalette.firstOrNull() ?: Color.White)
        internal set
    var drawStrokeWidthDp: Float by mutableStateOf(config.strokeWidthsDp.getOrNull(1) ?: 6f)
        internal set

    /** Brush handed to `InProgressStrokes`. `Brush.size` is in **pixels** (dp × density). */
    var drawBrush: Brush by mutableStateOf(makeDefaultBrush(drawColor, drawStrokeWidthDp, density))
        internal set

    fun addStroke(stroke: Stroke) = trace("imageeditor.last.draw.stroke.commit") {
        inkStrokes.add(stroke)
    }

    fun undoLastStroke() = trace("imageeditor.last.draw.undo") {
        if (inkStrokes.isNotEmpty()) inkStrokes.removeAt(inkStrokes.lastIndex)
    }

    fun clearStrokes() = trace("imageeditor.last.draw.clear") {
        inkStrokes.clear()
    }

    fun setDrawColor(color: Color) = trace("imageeditor.last.draw.color.change") {
        drawColor = color
        drawBrush = makeDefaultBrush(color, drawStrokeWidthDp, density)
    }

    fun setDrawStrokeWidth(widthDp: Float) = trace("imageeditor.last.draw.width.change") {
        drawStrokeWidthDp = widthDp
        drawBrush = makeDefaultBrush(drawColor, widthDp, density)
    }

    // --- Highlighter ------------------------------------------------------

    var highlighterColor: Color by mutableStateOf(
        config.drawingPalette.getOrNull(4) ?: Color(0xFFFDD835),
    )
        internal set
    var highlighterStrokeWidthDp: Float by mutableStateOf(
        config.highlighterStrokeWidthsDp.getOrNull(1) ?: 24f,
    )
        internal set

    fun setHighlighterColor(color: Color) = trace("imageeditor.last.highlighter.color.change") {
        highlighterColor = color
    }

    fun setHighlighterStrokeWidth(widthDp: Float) = trace("imageeditor.last.highlighter.width.change") {
        highlighterStrokeWidthDp = widthDp
    }

    // --- Text -------------------------------------------------------------

    val textItems: SnapshotStateList<LastTextItem> = mutableStateListOf()

    private var nextTextId: Long = 1L

    fun addText(item: LastTextItem): LastTextItem = trace("imageeditor.last.text.add") {
        val withId = item.copy(id = nextTextId++)
        textItems.add(withId)
        withId
    }

    fun updateText(item: LastTextItem) = trace("imageeditor.last.text.update") {
        val index = textItems.indexOfFirst { it.id == item.id }
        if (index >= 0) textItems[index] = item
    }

    fun removeText(id: Long) = trace("imageeditor.last.text.remove") {
        textItems.removeAll { it.id == id }
    }

    val hasEdits: Boolean
        get() = rotationDegrees != 0 ||
            flippedHorizontally ||
            !cropIsFull ||
            inkStrokes.isNotEmpty() ||
            textItems.isNotEmpty()
}

@Composable
fun rememberLastEditorState(config: LastImageEditorConfig): LastEditorState {
    val density = LocalDensity.current.density
    return remember(config, density) { LastEditorState(config, density) }
}

/**
 * Build a Jetpack Ink `Brush` from a Compose colour + stroke width in **dp**, converting to **px**
 * via `density`. See class-level kdoc on [LastEditorState] for why this differs from the modular
 * variant.
 */
private fun makeDefaultBrush(color: Color, strokeWidthDp: Float, density: Float): Brush {
    val argb = (color.alpha * 255f).toInt().shl(24) or
        (color.red * 255f).toInt().shl(16) or
        (color.green * 255f).toInt().shl(8) or
        (color.blue * 255f).toInt()
    return Brush.createWithColorIntArgb(
        family = androidx.ink.brush.StockBrushes.pressurePen(),
        colorIntArgb = argb,
        size = strokeWidthDp * density,
        epsilon = 0.1f,
    )
}
