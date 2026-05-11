/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.imageeditor.photoeditor

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.libraries.core.perf.BitmapDecoders
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import ja.burhanrashid52.photoeditor.PhotoFilter
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Third A/B comparison screen — backed by burhanrashid52/PhotoEditor 3.1.0.
 *
 * - **Drawing** → PhotoEditor's brush + eraser (View-based custom drawing on top of source ImageView)
 * - **Text** → PhotoEditor.addText (auto-positioned, draggable, scalable)
 * - **Filters** → PhotoEditor.setFilterEffect (real-time bitmap filters via GLSurfaceView/SurfaceView)
 * - **Save** → PhotoEditor.saveAsFile (the library's own composition pipeline)
 *
 * No crop tool — PhotoEditor doesn't provide one (per their README "Crop currently no").
 *
 * All trace section names start with `imageeditor.photoeditor.*` so the dashboard cards line up
 * alphabetically next to baseline (`imageeditor.*`) and modular (`imageeditor.modular.*`) for
 * three-way comparison.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorImageEditorScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberPhotoEditorState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = !isExporting) {
        if (state.activeTool != PhotoEditorTool.None) {
            state.selectTool(PhotoEditorTool.None)
        } else {
            onCancel()
        }
    }

    if (showTextDialog) {
        TextEntryDialog(
            initialColor = state.brushColor,
            onSubmit = { text, color ->
                state.addText(text, color)
                showTextDialog = false
            },
            onDismiss = { showTextDialog = false },
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        topBar = {
            TopAppBar(
                title = { Text("Edit (PhotoEditor)") },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !isExporting) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val ed = state.engine ?: return@IconButton
                            if (isExporting) return@IconButton
                            isExporting = true
                            scope.launch {
                                runCatching {
                                    PhotoEditorBitmapExporter.export(context, ed)
                                        .onSuccess { resultUri ->
                                            isExporting = false
                                            onConfirm(resultUri)
                                        }
                                        .onFailure { err ->
                                            Timber.e(err, "PhotoEditor export failed")
                                            isExporting = false
                                        }
                                }
                            }
                        },
                        enabled = !isExporting,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Apply")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            PhotoEditorBottomToolbar(
                state = state,
                onAddText = { showTextDialog = true },
                modifier = Modifier.systemBarsPadding(),
            )
        },
        containerColor = Color.Black,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PhotoEditorView(ctx).also { view ->
                        // FIT_CENTER + adjustViewBounds — without these, `setImageURI` on a
                        // content:// uri can land at the bitmap's intrinsic size which on most
                        // phones is far smaller than the canvas, so the image renders tiny
                        // until a filter triggers a re-layout.
                        view.source.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        view.source.adjustViewBounds = true
                        // Pre-decode and use setImageBitmap — more reliable than setImageURI
                        // across content/file URIs, and ensures the bitmap reaches the view at
                        // full resolution from the first frame. We downsample to a preview-sized
                        // bitmap (cap at PREVIEW_MAX_DIM on the longest side) so a 12 MP camera
                        // shot doesn't pin ~48 MB of native heap just to render at ~1440 px wide.
                        // PhotoEditor.saveAsFile still composes from the underlying View, so the
                        // user-visible quality is the preview quality — same trade-off the other
                        // two editors take.
                        val bitmap = runCatching {
                            BitmapDecoders.decodeForDisplay(
                                context = ctx,
                                uri = sourceUri,
                                maxWidth = PREVIEW_MAX_DIM,
                                maxHeight = PREVIEW_MAX_DIM,
                            )
                        }.getOrNull()
                        if (bitmap != null) {
                            view.source.setImageBitmap(bitmap)
                        } else {
                            view.source.setImageURI(sourceUri)
                        }
                        // Build the PhotoEditor controller. Pinch-text-scalable lets the user
                        // resize text items with two fingers, matching the library's defaults.
                        state.engine = PhotoEditor.Builder(ctx, view)
                            .setPinchTextScalable(true)
                            .build()
                    }
                },
            )
        }
    }

    if (isExporting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun PhotoEditorBottomToolbar(
    state: PhotoEditorState,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Brush controls — visible in Brush/Eraser mode.
        if (state.activeTool == PhotoEditorTool.Brush || state.activeTool == PhotoEditorTool.Eraser) {
            BrushSecondaryRow(state)
        }
        // Filter strip — visible in Filter mode.
        if (state.activeTool == PhotoEditorTool.Filter) {
            FilterSecondaryRow(state)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Filled.Brush, "Brush", state.activeTool == PhotoEditorTool.Brush) {
                state.selectTool(
                    if (state.activeTool == PhotoEditorTool.Brush) PhotoEditorTool.None
                    else PhotoEditorTool.Brush,
                )
            }
            ToolButton(Icons.Filled.Delete, "Eraser", state.activeTool == PhotoEditorTool.Eraser) {
                state.selectTool(
                    if (state.activeTool == PhotoEditorTool.Eraser) PhotoEditorTool.None
                    else PhotoEditorTool.Eraser,
                )
            }
            ToolButton(Icons.Filled.TextFields, "Text", state.activeTool == PhotoEditorTool.Text) {
                state.selectTool(PhotoEditorTool.Text)
                onAddText()
            }
            ToolButton(Icons.Filled.AutoFixHigh, "Filter", state.activeTool == PhotoEditorTool.Filter) {
                state.selectTool(
                    if (state.activeTool == PhotoEditorTool.Filter) PhotoEditorTool.None
                    else PhotoEditorTool.Filter,
                )
            }
            ToolButton(Icons.Filled.Undo, "Undo", false) { state.undo() }
            ToolButton(Icons.Filled.Redo, "Redo", false) { state.redo() }
        }
    }
}

@Composable
private fun BrushSecondaryRow(state: PhotoEditorState) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Color", style = MaterialTheme.typography.labelSmall)
            BRUSH_PALETTE.forEach { swatch ->
                val isSelected = swatch.value == state.brushColor.value
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(swatch, CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color.White else Color.Gray,
                            shape = CircleShape,
                        )
                        .clickable { state.setBrushColor(swatch) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Width", style = MaterialTheme.typography.labelSmall)
            BRUSH_WIDTHS.forEach { width ->
                val isSelected = width == state.brushSize
                Box(
                    modifier = Modifier
                        .size((width.coerceAtMost(28f) + 8f).dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.4f),
                            CircleShape,
                        )
                        .clickable { state.setBrushSize(width) },
                )
            }
        }
    }
}

@Composable
private fun FilterSecondaryRow(state: PhotoEditorState) {
    val filters = remember {
        listOf(
            PhotoFilter.NONE to "None",
            PhotoFilter.BRIGHTNESS to "Bright",
            PhotoFilter.CONTRAST to "Contrast",
            PhotoFilter.GRAY_SCALE to "Gray",
            PhotoFilter.SEPIA to "Sepia",
            PhotoFilter.NEGATIVE to "Negative",
            PhotoFilter.VIGNETTE to "Vignette",
            PhotoFilter.SHARPEN to "Sharpen",
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { (filter, label) ->
            val isSelected = state.activeFilter == filter
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { state.applyFilter(filter) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun TextEntryDialog(
    initialColor: Color,
    onSubmit: (String, Color) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    singleLine = false,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BRUSH_PALETTE.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(swatch, CircleShape)
                                .border(
                                    width = if (swatch.value == color.value) 2.dp else 1.dp,
                                    color = if (swatch.value == color.value) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { color = swatch },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onSubmit(text, color)
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// Cap for the preview bitmap's longest side. PhotoEditor's own save path renders from the View,
// so this is also effectively the export resolution — keep generous enough for share targets.
private const val PREVIEW_MAX_DIM = 2048

private val BRUSH_PALETTE = listOf(
    Color.White,
    Color(0xFFEF5350), // red
    Color(0xFFFF9800), // orange
    Color(0xFFFDD835), // amber
    Color(0xFF66BB6A), // green
    Color(0xFF42A5F5), // blue
    Color(0xFFAB47BC), // purple
    Color.Black,
)

private val BRUSH_WIDTHS = listOf(8f, 16f, 28f, 48f)
