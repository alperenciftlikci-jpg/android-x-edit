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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import io.element.android.libraries.core.perf.BitmapDecoders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.element.android.libraries.imageeditor.render.BitmapExporter
import io.element.android.libraries.imageeditor.state.EditorState
import io.element.android.libraries.imageeditor.state.EditorTool
import io.element.android.libraries.imageeditor.state.rememberEditorState
import io.element.android.libraries.imageeditor.tools.crop.CropOverlay
import io.element.android.libraries.imageeditor.tools.crop.CropRect
import io.element.android.libraries.imageeditor.tools.draw.DrawCanvas
import io.element.android.libraries.imageeditor.tools.text.TextEditorScreen
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

    // Whole "user is in the editor screen" session — records on dispose. Tells you the
    // typical session length. FPS is suppressed (recordFps = false) because a session that
    // sits idle for several seconds reads as artificially low FPS — Compose only invalidates
    // when something changes, so JankStats doesn't observe a frame every 16 ms when the screen
    // is static, and `framesDuring / wallClockDuration` collapses to single digits. Per-tool
    // gesture traces are where you want to read FPS; this trace is for duration + jank events.
    val editorSession = remember {
        io.element.android.libraries.core.perf.TracedGesture(
            name = "imageeditor.screen.session",
            recordFps = false,
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        editorSession.start()
        onDispose { editorSession.finish() }
    }

    var isExporting by remember { mutableStateOf(false) }
    var editingTextItem by remember { mutableStateOf<TextItem?>(null) }
    var pendingNewText by remember { mutableStateOf(false) }
    val textEditorOpen = pendingNewText || editingTextItem != null

    BackHandler(enabled = !isExporting) {
        // First back press deselects the active tool (so an accidental edge-swipe
        // while cropping doesn't dump all edits). Second back press closes editor.
        if (state.activeTool != EditorTool.None) {
            state.selectTool(EditorTool.None)
        } else {
            onCancel()
        }
    }

    // Overlay pattern: ImageEditorScreen's Scaffold + state stay composed underneath, the text
    // editor opens on top of it. The previous `if (textEditorOpen) TextEditorScreen() else
    // mainEditor()` pattern disposed the entire main editor (including the decoded bitmap, the
    // ImageStage layout, the AsyncImage's pinned content) every time the user opened the text
    // editor, then re-instantiated it on dismiss — that's the "another screen is opening" feel
    // the user reported. Now main editor remains in the Compose tree (just hidden under the text
    // editor's opaque black background) and the transition is a single Box swap.
    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier
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

        // Text editor overlay. Rendered on top of the main editor when active; its own opaque
        // black background hides what's underneath, and its IME-aware Foundation layout takes
        // focus. The main editor's Scaffold + decoded bitmap stay composed but invisible — when
        // the text editor closes, returning to the main editor is a single Box swap, no
        // re-decode + re-layout cold-paint.
        if (textEditorOpen) {
            TextEditorScreen(
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
    } // end Box overlay container
}

@Composable
private fun ImageStage(
    sourceUri: Uri,
    state: EditorState,
    onTextItemTap: (TextItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Decode the source's intrinsic dimensions once so the on-screen stage is
    // sized to the image's aspect ratio. This is what makes the overlay
    // coordinates line up with the exported bitmap — without it, ContentScale.Fit
    // letterboxes the image inside an arbitrary container while paths are
    // normalized against that container.
    val context = LocalContext.current
    LaunchedEffect(sourceUri) {
        if (state.imageIntrinsicSize != IntSize.Zero) return@LaunchedEffect
        // First-decode of the source URI — happens once when the editor opens, before any tools
        // are usable. Long durations here = slow-to-open editor.
        val size = io.element.android.libraries.core.perf.traceAsync("imageeditor.screen.image.decode") {
            withContext(Dispatchers.IO) {
                BitmapDecoders.decodeBoundsOnly(context, sourceUri)
                    ?.let { IntSize(it.width, it.height) }
                    ?: IntSize.Zero
            }
        }
        if (size != IntSize.Zero) state.imageIntrinsicSize = size
    }

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

    // Effective ratio: while the user has rotated by 90/270, the visible image
    // swaps width/height — keep the on-screen Box's aspect in sync so the image
    // fills it and overlays stay aligned with what the bitmap will be.
    val intrinsicRatio = if (state.imageIntrinsicSize.height > 0) {
        state.imageIntrinsicSize.width.toFloat() / state.imageIntrinsicSize.height
    } else {
        1f
    }
    val rotated90 = (state.rotationDegrees % 180) != 0
    val effectiveRatio = if (rotated90) 1f / intrinsicRatio else intrinsicRatio

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .aspectRatio(effectiveRatio.coerceAtLeast(0.01f))
                .onSizeChanged { state.canvasSizePx = it },
            contentAlignment = Alignment.Center,
        ) {
            // Image fills the aspect-ratio'd box. We rotate via graphicsLayer and
            // then scale to fill the rotated bounds when the rotation is 90/270.
            val rotation = state.rotationDegrees.toFloat()
            val flipScale = if (state.flippedHorizontally) -1f else 1f
            // After 90° rotation the image needs to be scaled by the inverse aspect
            // so it covers the rotated container fully.
            val coverScale = if (rotated90) intrinsicRatio else 1f
            AsyncImage(
                model = sourceUri,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        rotationZ = rotation,
                        scaleX = flipScale * coverScale,
                        scaleY = coverScale,
                    ),
            )

            // Draw layer — visible always; interactive when Draw or Highlighter is
            // selected. Highlighter shares the canvas but applies a fixed alpha so
            // the stroke does not occlude what's underneath.
            val isHighlighter = state.activeTool == EditorTool.Highlighter
            val activeColor = if (isHighlighter) {
                state.highlighterColor.copy(alpha = state.config.highlighterAlpha)
            } else {
                state.drawColor
            }
            val activeStroke = if (isHighlighter) {
                state.highlighterStrokeWidthDp
            } else {
                state.drawStrokeWidthDp
            }
            DrawCanvas(
                paths = state.drawnPaths,
                color = activeColor,
                strokeWidthDp = activeStroke,
                enabled = state.activeTool == EditorTool.Draw || isHighlighter,
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
}
