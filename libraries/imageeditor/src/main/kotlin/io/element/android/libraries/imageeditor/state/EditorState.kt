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

    // --- Rotate / flip ----------------------------------------------------

    /** Total rotation applied, always a multiple of 90°, normalized to [0, 360). */
    var rotationDegrees: Int by mutableStateOf(0)
        internal set

    /** Mirror horizontally. */
    var flippedHorizontally: Boolean by mutableStateOf(false)
        internal set

    fun rotate90Clockwise() {
        rotationDegrees = (rotationDegrees + 90) % 360
    }

    fun toggleHorizontalFlip() {
        flippedHorizontally = !flippedHorizontally
    }

    // --- Crop -------------------------------------------------------------

    var cropRect: CropRect by mutableStateOf(CropRect.Full)
        internal set

    var selectedAspectRatio: AspectRatioPreset by mutableStateOf(AspectRatioPreset.Free)
        internal set

    fun resetCrop() {
        cropRect = CropRect.Full
        selectedAspectRatio = AspectRatioPreset.Free
    }

    fun applyCrop(rect: CropRect, preset: AspectRatioPreset) {
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

    fun addPath(path: DrawingPath) {
        drawnPaths.add(path)
    }

    fun undoLastPath() {
        if (drawnPaths.isNotEmpty()) {
            drawnPaths.removeAt(drawnPaths.lastIndex)
        }
    }

    fun clearPaths() {
        drawnPaths.clear()
    }

    fun setDrawColor(color: Color) {
        drawColor = color
    }

    fun setDrawStrokeWidth(widthDp: Float) {
        drawStrokeWidthDp = widthDp
    }

    // --- Text -------------------------------------------------------------

    val textItems: SnapshotStateList<TextItem> = mutableStateListOf()

    private var nextTextId: Long = 1L

    fun addText(item: TextItem): TextItem {
        val withId = item.copy(id = nextTextId++)
        textItems.add(withId)
        return withId
    }

    fun updateText(item: TextItem) {
        val index = textItems.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            textItems[index] = item
        }
    }

    fun removeText(id: Long) {
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
