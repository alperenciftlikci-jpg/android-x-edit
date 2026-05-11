/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.imageeditor.photoeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.element.android.libraries.core.perf.trace
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoFilter

/**
 * Tools the toolbar can route the user into. Some of these (Filter / Emoji / Sticker) translate
 * to PhotoEditor library calls that don't have a "tool mode" — we just take the action immediately
 * when the corresponding button is tapped.
 */
enum class PhotoEditorTool { None, Brush, Eraser, Text, Filter }

/**
 * Mutable state holder for the PhotoEditor-backed editor. Wraps every user action in a `trace { }`
 * with the `imageeditor.photoeditor.*` prefix so the dashboard cards alphabetically cluster next
 * to the baseline (`imageeditor.*`) and the uCrop+Ink modular (`imageeditor.modular.*`) cards.
 *
 * The actual editing engine is [PhotoEditor] from burhanrashid52/photoeditor (3.1.0). We hold a
 * lateinit reference to it so the screen can hand it over after `PhotoEditorView` is composed.
 */
@Stable
class PhotoEditorState internal constructor() {
    /** Set by the screen once `PhotoEditor.Builder(ctx, view).build()` resolves. */
    var engine: PhotoEditor? = null
        internal set

    var activeTool: PhotoEditorTool by mutableStateOf(PhotoEditorTool.None)
        internal set

    var brushColor: Color by mutableStateOf(Color.Red)
        internal set

    var brushSize: Float by mutableStateOf(20f)
        internal set

    var activeFilter: PhotoFilter by mutableStateOf(PhotoFilter.NONE)
        internal set

    fun selectTool(tool: PhotoEditorTool) = trace("imageeditor.photoeditor.tool.select") {
        activeTool = tool
        val ed = engine ?: return@trace
        when (tool) {
            PhotoEditorTool.Brush -> {
                ed.setBrushDrawingMode(true)
                ed.brushColor = brushColor.toArgb()
                ed.brushSize = brushSize
            }
            PhotoEditorTool.Eraser -> {
                // PhotoEditor 3.x: brushEraser() flips the brush into erase mode and reuses
                // brushSize for the eraser thickness.
                ed.brushEraser()
            }
            PhotoEditorTool.None,
            PhotoEditorTool.Text,
            PhotoEditorTool.Filter -> ed.setBrushDrawingMode(false)
        }
    }

    fun setBrushColor(color: Color) = trace("imageeditor.photoeditor.brush.color.change") {
        brushColor = color
        engine?.brushColor = color.toArgb()
    }

    fun setBrushSize(size: Float) = trace("imageeditor.photoeditor.brush.width.change") {
        brushSize = size
        engine?.brushSize = size
    }

    fun addText(text: String, color: Color) = trace("imageeditor.photoeditor.text.add") {
        engine?.addText(text, color.toArgb())
    }

    fun applyFilter(filter: PhotoFilter) = trace("imageeditor.photoeditor.filter.apply") {
        activeFilter = filter
        engine?.setFilterEffect(filter)
    }

    fun undo() = trace("imageeditor.photoeditor.undo") {
        engine?.undo()
    }

    fun redo() = trace("imageeditor.photoeditor.redo") {
        engine?.redo()
    }

    fun clearAll() = trace("imageeditor.photoeditor.clear") {
        engine?.clearAllViews()
    }
}

@Composable
fun rememberPhotoEditorState(): PhotoEditorState =
    remember { PhotoEditorState() }
