/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.imageeditor.modular

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.ink.brush.Brush
import io.element.android.libraries.core.perf.trace

/**
 * Mutable state holder for the modular editor. Same shape as the baseline `EditorState`, but every
 * action wraps with `imageeditor.modular.*` trace sections (note the **`.modular.`** infix) so the
 * dashboard cards land alphabetically next to the baseline `imageeditor.*` cards for easy compare.
 */
@Stable
class ModularEditorState internal constructor(
    val config: ModularImageEditorConfig,
) {
    var activeTool: ModularEditorTool by mutableStateOf(ModularEditorTool.None)
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

    fun rotate90Clockwise() = trace("imageeditor.modular.transform.rotate") {
        rotationDegrees = (rotationDegrees + 90) % 360
    }

    fun toggleHorizontalFlip() = trace("imageeditor.modular.transform.flip") {
        flippedHorizontally = !flippedHorizontally
    }

    fun selectTool(tool: ModularEditorTool) = trace("imageeditor.modular.tool.select") {
        activeTool = tool
    }

    // --- Crop (uCrop adapter writes here) --------------------------------

    /**
     * Normalised crop rect in image-space (0..1 each side). uCrop writes its result here when the
     * user taps "Apply" inside [io.element.android.libraries.imageeditor.modular.tools.crop.UCropView].
     */
    var cropLeft: Float by mutableStateOf(0f); internal set
    var cropTop: Float by mutableStateOf(0f); internal set
    var cropRight: Float by mutableStateOf(1f); internal set
    var cropBottom: Float by mutableStateOf(1f); internal set

    val cropIsFull: Boolean
        get() = cropLeft == 0f && cropTop == 0f && cropRight == 1f && cropBottom == 1f

    fun resetCrop() = trace("imageeditor.modular.crop.reset") {
        cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f
    }

    fun applyCrop(l: Float, t: Float, r: Float, b: Float) = trace("imageeditor.modular.crop.apply") {
        cropLeft = l; cropTop = t; cropRight = r; cropBottom = b
    }

    // --- Drawing (Ink adapter writes finished strokes here) --------------

    /**
     * Strokes finished by the Jetpack Ink `InProgressStrokes` callback. Each entry is one user
     * gesture from pen-down to pen-up.
     */
    val inkStrokes: SnapshotStateList<androidx.ink.strokes.Stroke> = mutableStateListOf()

    var drawColor: Color by mutableStateOf(config.drawingPalette.firstOrNull() ?: Color.White)
        internal set
    var drawStrokeWidthDp: Float by mutableStateOf(config.strokeWidthsDp.getOrNull(1) ?: 6f)
        internal set

    /** Brush handed to `InProgressStrokes`. Recomputed when colour or stroke width changes. */
    var drawBrush: Brush by mutableStateOf(makeDefaultBrush(drawColor, drawStrokeWidthDp))
        internal set

    fun addStroke(stroke: androidx.ink.strokes.Stroke) = trace("imageeditor.modular.draw.stroke.commit") {
        inkStrokes.add(stroke)
    }

    fun undoLastStroke() = trace("imageeditor.modular.draw.undo") {
        if (inkStrokes.isNotEmpty()) inkStrokes.removeAt(inkStrokes.lastIndex)
    }

    fun clearStrokes() = trace("imageeditor.modular.draw.clear") {
        inkStrokes.clear()
    }

    fun setDrawColor(color: Color) = trace("imageeditor.modular.draw.color.change") {
        drawColor = color
        drawBrush = makeDefaultBrush(color, drawStrokeWidthDp)
    }

    fun setDrawStrokeWidth(widthDp: Float) = trace("imageeditor.modular.draw.width.change") {
        drawStrokeWidthDp = widthDp
        drawBrush = makeDefaultBrush(drawColor, widthDp)
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

    fun setHighlighterColor(color: Color) = trace("imageeditor.modular.highlighter.color.change") {
        highlighterColor = color
    }

    fun setHighlighterStrokeWidth(widthDp: Float) = trace("imageeditor.modular.highlighter.width.change") {
        highlighterStrokeWidthDp = widthDp
    }

    // --- Text -------------------------------------------------------------

    val textItems: SnapshotStateList<ModularTextItem> = mutableStateListOf()

    private var nextTextId: Long = 1L

    fun addText(item: ModularTextItem): ModularTextItem = trace("imageeditor.modular.text.add") {
        val withId = item.copy(id = nextTextId++)
        textItems.add(withId)
        withId
    }

    fun updateText(item: ModularTextItem) = trace("imageeditor.modular.text.update") {
        val index = textItems.indexOfFirst { it.id == item.id }
        if (index >= 0) textItems[index] = item
    }

    fun removeText(id: Long) = trace("imageeditor.modular.text.remove") {
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
fun rememberModularEditorState(config: ModularImageEditorConfig): ModularEditorState =
    remember(config) { ModularEditorState(config) }

/**
 * Build a Jetpack Ink `Brush` from a Compose colour + stroke width in dp. We pick the stock
 * "pressure pen" family for now; the highlighter equivalent (`highlighter()`) can swap in later.
 */
private fun makeDefaultBrush(color: Color, strokeWidthDp: Float): Brush {
    val argb = (color.alpha * 255f).toInt().shl(24) or
        (color.red * 255f).toInt().shl(16) or
        (color.green * 255f).toInt().shl(8) or
        (color.blue * 255f).toInt()
    return Brush.createWithColorIntArgb(
        family = androidx.ink.brush.StockBrushes.pressurePen(),
        colorIntArgb = argb,
        size = strokeWidthDp,
        epsilon = 0.1f,
    )
}
