/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.state

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
import io.element.android.libraries.core.perf.trace
import io.element.android.libraries.imageeditor.AspectRatioPreset
import io.element.android.libraries.imageeditor.ImageEditorConfig
import io.element.android.libraries.imageeditor.tools.crop.CropRect
import io.element.android.libraries.imageeditor.tools.draw.DrawingPath
import io.element.android.libraries.imageeditor.tools.text.TextItem

/**
 * Mutable state holder for the editor. Owned by [io.element.android.libraries.imageeditor.ImageEditorScreen]
 * but exposed so individual tools (draw/text/crop/rotate) can read and update it.
 */
@Stable
class EditorState internal constructor(
    val config: ImageEditorConfig,
) {
    var activeTool: EditorTool by mutableStateOf(EditorTool.None)
        internal set

    /**
     * Pixel size of the area where the image and overlays are rendered. Captured
     * from the on-screen Box via `onSizeChanged`. Used by [io.element.android.libraries.imageeditor.render.BitmapExporter]
     * to scale strokes / text from canvas-px to bitmap-px so what the user saw
     * matches what gets baked.
     */
    var canvasSizePx: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** Intrinsic dimensions of the source bitmap, decoded once from the URI. */
    var imageIntrinsicSize: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    // --- Rotate / flip ----------------------------------------------------

    /** Total rotation applied, always a multiple of 90°, normalized to [0, 360). */
    var rotationDegrees: Int by mutableStateOf(0)
        internal set

    /** Mirror horizontally. */
    var flippedHorizontally: Boolean by mutableStateOf(false)
        internal set

    fun rotate90Clockwise() = trace("imageeditor.transform.rotate") {
        rotationDegrees = (rotationDegrees + 90) % 360
    }

    fun toggleHorizontalFlip() = trace("imageeditor.transform.flip") {
        flippedHorizontally = !flippedHorizontally
    }

    /**
     * Switch the active tool. Routed through here (instead of a direct assignment to
     * [activeTool]) so each switch lands in the perf dashboard as its own section.
     */
    fun selectTool(tool: EditorTool) = trace("imageeditor.tool.select") {
        activeTool = tool
    }

    // --- Crop -------------------------------------------------------------

    var cropRect: CropRect by mutableStateOf(CropRect.Full)
        internal set

    var selectedAspectRatio: AspectRatioPreset by mutableStateOf(AspectRatioPreset.Free)
        internal set

    fun resetCrop() = trace("imageeditor.crop.reset") {
        cropRect = CropRect.Full
        selectedAspectRatio = AspectRatioPreset.Free
    }

    fun applyCrop(rect: CropRect, preset: AspectRatioPreset) = trace("imageeditor.crop.apply") {
        cropRect = rect
        selectedAspectRatio = preset
    }

    // --- Drawing ----------------------------------------------------------

    val drawnPaths: SnapshotStateList<DrawingPath> = mutableStateListOf()

    var drawColor: Color by mutableStateOf(config.drawingPalette.firstOrNull() ?: Color.White)
        internal set

    var drawStrokeWidthDp: Float by mutableStateOf(
        config.strokeWidthsDp.getOrNull(1) ?: 6f,
    )
        internal set

    fun addPath(path: DrawingPath) = trace("imageeditor.draw.stroke.commit") {
        drawnPaths.add(path)
    }

    fun undoLastPath() = trace("imageeditor.draw.undo") {
        if (drawnPaths.isNotEmpty()) {
            drawnPaths.removeAt(drawnPaths.lastIndex)
        }
    }

    fun clearPaths() = trace("imageeditor.draw.clear") {
        drawnPaths.clear()
    }

    fun setDrawColor(color: Color) = trace("imageeditor.draw.color.change") {
        drawColor = color
    }

    fun setDrawStrokeWidth(widthDp: Float) = trace("imageeditor.draw.width.change") {
        drawStrokeWidthDp = widthDp
    }

    // --- Highlighter ------------------------------------------------------

    var highlighterColor: Color by mutableStateOf(
        // Default to a yellow-ish swatch from the palette; fall back to yellow
        // (HEX FDD835 in our default palette) so the highlighter feels familiar.
        config.drawingPalette.getOrNull(4) ?: Color(0xFFFDD835),
    )
        internal set

    var highlighterStrokeWidthDp: Float by mutableStateOf(
        config.highlighterStrokeWidthsDp.getOrNull(1) ?: 24f,
    )
        internal set

    fun setHighlighterColor(color: Color) = trace("imageeditor.highlighter.color.change") {
        highlighterColor = color
    }

    fun setHighlighterStrokeWidth(widthDp: Float) = trace("imageeditor.highlighter.width.change") {
        highlighterStrokeWidthDp = widthDp
    }

    // --- Text -------------------------------------------------------------

    val textItems: SnapshotStateList<TextItem> = mutableStateListOf()

    private var nextTextId: Long = 1L

    fun addText(item: TextItem): TextItem = trace("imageeditor.text.add") {
        val withId = item.copy(id = nextTextId++)
        textItems.add(withId)
        withId
    }

    fun updateText(item: TextItem) = trace("imageeditor.text.update") {
        val index = textItems.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            textItems[index] = item
        }
    }

    fun removeText(id: Long) = trace("imageeditor.text.remove") {
        textItems.removeAll { it.id == id }
    }

    // --- General ----------------------------------------------------------

    /** Whether the user has done any modification worth committing. */
    val hasEdits: Boolean
        get() = rotationDegrees != 0 ||
            flippedHorizontally ||
            !cropRect.isFull ||
            drawnPaths.isNotEmpty() ||
            textItems.isNotEmpty()
}

@Composable
fun rememberEditorState(config: ImageEditorConfig): EditorState =
    remember(config) { EditorState(config) }
