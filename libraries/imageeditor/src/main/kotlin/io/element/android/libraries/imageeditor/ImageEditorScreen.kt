/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import io.element.android.libraries.imageeditor.render.BitmapExporter
import io.element.android.libraries.imageeditor.state.EditorState
import io.element.android.libraries.imageeditor.state.EditorTool
import io.element.android.libraries.imageeditor.state.rememberEditorState
import io.element.android.libraries.imageeditor.tools.crop.CropOverlay
import io.element.android.libraries.imageeditor.tools.crop.CropRect
import io.element.android.libraries.imageeditor.tools.draw.DrawCanvas
import io.element.android.libraries.imageeditor.tools.text.TextEditDialog
import io.element.android.libraries.imageeditor.tools.text.TextItem
import io.element.android.libraries.imageeditor.tools.text.TextOverlay
import io.element.android.libraries.imageeditor.ui.BottomToolbar
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Public entry point for the image editor.
 *
 * Renders a full-screen scaffold with:
 *  - top bar (cancel / confirm),
 *  - image preview that follows live state (rotation, flip, crop, drawings, text),
 *  - bottom toolbar with the four tools.
 *
 * On confirm, all edits are flattened to a new bitmap saved in cache and the
 * resulting [Uri] is delivered via [onConfirm]. On cancel [onCancel] is invoked
 * with no side effects.
 *
 * The Composable is theme-agnostic: it consumes the host's [MaterialTheme], so it
 * works inside Element X (which sets ElementTheme on top of Material3) and inside
 * any other Compose app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    config: ImageEditorConfig = ImageEditorConfig.Default,
) {
    val state = rememberEditorState(config)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    var editingTextItem by remember { mutableStateOf<TextItem?>(null) }
    var pendingNewText by remember { mutableStateOf(false) }

    BackHandler(enabled = !isExporting) { onCancel() }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !isExporting) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (isExporting) return@IconButton
                            isExporting = true
                            scope.launch {
                                BitmapExporter.export(context, sourceUri, state, density)
                                    .onSuccess { resultUri ->
                                        isExporting = false
                                        onConfirm(resultUri)
                                    }
                                    .onFailure { err ->
                                        Timber.e(err, "Image editor export failed")
                                        isExporting = false
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
            BottomToolbar(
                state = state,
                onAddText = {
                    pendingNewText = true
                    editingTextItem = null
                },
                modifier = Modifier.systemBarsPadding(),
            )
        },
        containerColor = Color.Black,
    ) { paddingValues ->
        ImageStage(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            sourceUri = sourceUri,
            state = state,
            onTextItemTap = { editingTextItem = it; pendingNewText = false },
        )
    }

    // Text edit / add dialog.
    if (pendingNewText || editingTextItem != null) {
        TextEditDialog(
            initial = editingTextItem,
            palette = config.drawingPalette,
            onSubmit = { item ->
                if (editingTextItem == null) {
                    state.addText(item)
                } else {
                    state.updateText(item)
                }
                pendingNewText = false
                editingTextItem = null
            },
            onDelete = if (editingTextItem != null) {
                { existing ->
                    state.removeText(existing.id)
                    pendingNewText = false
                    editingTextItem = null
                }
            } else {
                null
            },
            onDismiss = {
                pendingNewText = false
                editingTextItem = null
            },
        )
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
private fun ImageStage(
    sourceUri: Uri,
    state: EditorState,
    onTextItemTap: (TextItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // When the aspect ratio chip changes, snap the crop rect to a centered rect
    // that respects the new ratio. This is approximate (we don't know the actual
    // image aspect at this layer) but keeps the UX usable until the user adjusts.
    LaunchedEffect(state.selectedAspectRatio) {
        val ratio = state.selectedAspectRatio.ratio ?: return@LaunchedEffect
        // Build a centered rect with the given ratio inside (0..1, 0..1) viewport.
        val targetWidth: Float
        val targetHeight: Float
        if (ratio >= 1f) {
            targetWidth = 1f
            targetHeight = (1f / ratio).coerceAtMost(1f)
        } else {
            targetHeight = 1f
            targetWidth = ratio.coerceAtMost(1f)
        }
        val left = (1f - targetWidth) / 2f
        val top = (1f - targetHeight) / 2f
        state.cropRect = CropRect(left, top, left + targetWidth, top + targetHeight)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Image with live transformations.
        AsyncImage(
            model = sourceUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = state.rotationDegrees.toFloat(),
                    scaleX = if (state.flippedHorizontally) -1f else 1f,
                ),
        )

        // Draw layer — visible always, interactive only when Draw is selected.
        DrawCanvas(
            paths = state.drawnPaths,
            color = state.drawColor,
            strokeWidthDp = state.drawStrokeWidthDp,
            enabled = state.activeTool == EditorTool.Draw,
            onPathFinished = state::addPath,
            modifier = Modifier.fillMaxSize(),
        )

        // Text layer — visible always, draggable/tappable only when Text is selected.
        TextOverlay(
            items = state.textItems,
            enabled = state.activeTool == EditorTool.Text,
            onItemMoved = state::updateText,
            onTextTap = onTextItemTap,
            modifier = Modifier.fillMaxSize(),
        )

        // Crop overlay — visible only while cropping.
        if (state.activeTool == EditorTool.Crop) {
            CropOverlay(
                rect = state.cropRect,
                aspectRatio = state.selectedAspectRatio.ratio,
                onRectChange = { state.cropRect = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
