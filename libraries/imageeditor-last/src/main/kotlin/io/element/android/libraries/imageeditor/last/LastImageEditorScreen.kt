/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last

import android.graphics.Bitmap
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yalantis.ucrop.callback.BitmapCropCallback
import com.yalantis.ucrop.view.UCropView
import io.element.android.libraries.core.perf.BitmapDecoders
import io.element.android.libraries.core.perf.traceAsync
import io.element.android.libraries.imageeditor.last.render.LastBitmapExporter
import io.element.android.libraries.imageeditor.last.tools.crop.LastUCropTool
import io.element.android.libraries.imageeditor.last.tools.draw.LastInkDrawTool
import io.element.android.libraries.imageeditor.last.tools.text.LastTextEditorScreen
import io.element.android.libraries.imageeditor.last.tools.text.LastTextOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume

private const val PREVIEW_MAX_DIM = 2048

/**
 * Public entry point for the **last** (hybrid) image editor.
 *
 *  - **Crop / Rotate / Flip** → uCrop ([LastUCropTool]).
 *  - **Drawing** → Jetpack Ink ([LastInkDrawTool]) with density-corrected brush size and
 *    AndroidView-hosted committed strokes layer.
 *  - **Text** → native EditText editor screen ([LastTextEditorScreen]) + draggable on-canvas
 *    overlay ([LastTextOverlay]) with two-pass StaticLayout export alignment.
 *
 * Trace prefix `imageeditor.last.*` so dashboard cards group separately from the other variants.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastImageEditorScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    config: LastImageEditorConfig = LastImageEditorConfig(),
) {
    val state = rememberLastEditorState(config)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    var uCropViewRef by remember { mutableStateOf<UCropView?>(null) }
    var hasCroppedOnce by remember { mutableStateOf(false) }

    var editingTextItem by remember { mutableStateOf<LastTextItem?>(null) }
    var pendingNewText by remember { mutableStateOf(false) }
    val textEditorOpen = pendingNewText || editingTextItem != null

    BackHandler(enabled = !isExporting && !textEditorOpen) {
        if (state.activeTool != LastEditorTool.None) {
            state.selectTool(LastEditorTool.None)
        } else {
            onCancel()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            topBar = {
                TopAppBar(
                    title = { Text("Edit (last)") },
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
                                    runApply(
                                        context = context,
                                        sourceUri = sourceUri,
                                        state = state,
                                        density = density,
                                        uCropView = uCropViewRef.takeIf { hasCroppedOnce },
                                        onConfirm = onConfirm,
                                        onFinished = { isExporting = false },
                                    )
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
                LastBottomToolbar(
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
                onUCropViewReady = {
                    uCropViewRef = it
                    hasCroppedOnce = true
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

        // Overlay text editor — main editor stays composed underneath, no cold-paint on dismiss.
        if (textEditorOpen) {
            LastTextEditorScreen(
                initial = editingTextItem,
                palette = config.drawingPalette,
                onSubmit = { item ->
                    if (editingTextItem == null) state.addText(item) else state.updateText(item)
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
    }
}

@Composable
private fun ImageStage(
    sourceUri: Uri,
    state: LastEditorState,
    onTextItemTap: (LastTextItem) -> Unit,
    onUCropViewReady: (UCropView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Store the preview as a plain Android Bitmap (not ImageBitmap) — we render it through an
    // AndroidView ImageView, not a Compose Image. See the rationale below where the ImageView
    // is mounted.
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sourceUri) {
        val (bm, intrinsic) = withContext(Dispatchers.IO) {
            val intrinsic = BitmapDecoders.decodeBoundsOnly(context, sourceUri)
                ?.let { IntSize(it.width, it.height) }
                ?: IntSize.Zero
            val bitmap = BitmapDecoders.decodeForDisplay(
                context = context,
                uri = sourceUri,
                maxWidth = PREVIEW_MAX_DIM,
                maxHeight = PREVIEW_MAX_DIM,
            )
            bitmap to intrinsic
        }
        if (intrinsic != IntSize.Zero) state.imageIntrinsicSize = intrinsic
        previewBitmap = bm
    }

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
            if (state.activeTool == LastEditorTool.Crop) {
                LastUCropTool(
                    sourceUri = sourceUri,
                    rotationDegreesAdditive = state.rotationDegrees,
                    aspectRatio = null,
                    modifier = Modifier.fillMaxSize(),
                    onUCropViewReady = onUCropViewReady,
                )
            } else {
                val rotation = state.rotationDegrees.toFloat()
                val flipScale = if (state.flippedHorizontally) -1f else 1f
                val coverScale = if (rotated90) intrinsicRatio else 1f
                // Image rendered via AndroidView + ImageView, NOT Compose `Image`. The Compose
                // `Image` (even with `CompositingStrategy.Offscreen`) was getting GPU-buffer
                // corrupted as soon as `InProgressStrokes`' SurfaceView mounted on top — Surface
                // hole-punching on the parent window flushes some Skia buffers asymmetrically,
                // and on certain devices/drivers the result is the visible artifact pattern
                // (image fragments scattered across the canvas) the user reported in modular too.
                // Hosting the image as a sibling `View` to the SurfaceView (instead of as a
                // Compose RenderNode) gives us standard View buffer compositing, which respects
                // the SurfaceView z-order without corrupting the underlying content.
                //
                // graphicsLayer applies to the AndroidView's outer wrapper in the Compose tree —
                // the View hierarchy beneath sees the rotated/scaled bounds, so rotation and flip
                // still work as before.
                previewBitmap?.let { bm ->
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                rotationZ = rotation,
                                scaleX = flipScale * coverScale,
                                scaleY = coverScale,
                            ),
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                // FIT_XY = stretch to bounds, equivalent to Compose's
                                // ContentScale.FillBounds. The aspect ratio is enforced by the
                                // outer `Box(.aspectRatio(effectiveRatio))`, so FIT_XY won't
                                // visibly distort the image.
                                scaleType = ImageView.ScaleType.FIT_XY
                                setImageBitmap(bm)
                            }
                        },
                        update = { iv ->
                            // Avoid re-binding the same bitmap — that triggers an internal
                            // Drawable invalidate which would force the ImageView to repaint
                            // and reallocate buffers.
                            if (iv.drawable !is android.graphics.drawable.BitmapDrawable ||
                                (iv.drawable as android.graphics.drawable.BitmapDrawable).bitmap !== bm
                            ) {
                                iv.setImageBitmap(bm)
                            }
                        },
                    )
                }

                LastInkDrawTool(
                    brush = state.drawBrush,
                    committedStrokes = state.inkStrokes,
                    enabled = state.activeTool == LastEditorTool.Draw ||
                        state.activeTool == LastEditorTool.Highlighter,
                    onStrokeFinished = state::addStroke,
                    modifier = Modifier.fillMaxSize(),
                )

                LastTextOverlay(
                    items = state.textItems,
                    enabled = state.activeTool == LastEditorTool.Text,
                    onItemMoved = state::updateText,
                    onTextTap = onTextItemTap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LastBottomToolbar(
    state: LastEditorState,
    onAddText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (state.activeTool == LastEditorTool.Draw || state.activeTool == LastEditorTool.Highlighter) {
            DrawSecondaryRow(state = state)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(
                icon = Icons.Filled.Crop,
                label = "Crop",
                selected = state.activeTool == LastEditorTool.Crop,
                onClick = {
                    state.selectTool(
                        if (state.activeTool == LastEditorTool.Crop) LastEditorTool.None
                        else LastEditorTool.Crop,
                    )
                },
            )
            ToolButton(
                icon = Icons.Filled.Rotate90DegreesCw,
                label = "Rotate",
                selected = false,
                onClick = { state.rotate90Clockwise() },
            )
            ToolButton(
                icon = Icons.Filled.Refresh,
                label = "Flip",
                selected = false,
                onClick = { state.toggleHorizontalFlip() },
            )
            ToolButton(
                icon = Icons.Filled.Brush,
                label = "Draw",
                selected = state.activeTool == LastEditorTool.Draw,
                onClick = {
                    state.selectTool(
                        if (state.activeTool == LastEditorTool.Draw) LastEditorTool.None
                        else LastEditorTool.Draw,
                    )
                },
            )
            ToolButton(
                icon = Icons.Filled.TextFields,
                label = "Text",
                selected = state.activeTool == LastEditorTool.Text,
                onClick = {
                    state.selectTool(LastEditorTool.Text)
                    onAddText()
                },
            )
            ToolButton(
                icon = Icons.Filled.Delete,
                label = "Undo",
                selected = false,
                onClick = { state.undoLastStroke() },
            )
        }
    }
}

@Composable
private fun DrawSecondaryRow(state: LastEditorState) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Color", style = MaterialTheme.typography.labelSmall)
            state.config.drawingPalette.forEach { swatch ->
                val isSelected = swatch.value == state.drawColor.value
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(swatch, CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color.White else Color.Gray,
                            shape = CircleShape,
                        )
                        .clickable { state.setDrawColor(swatch) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Width", style = MaterialTheme.typography.labelSmall)
            state.config.strokeWidthsDp.forEach { width ->
                val isSelected = width == state.drawStrokeWidthDp
                Box(
                    modifier = Modifier
                        .size((width.coerceAtMost(20f) + 8f).dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.4f),
                            CircleShape,
                        )
                        .clickable { state.setDrawStrokeWidth(width) },
                )
            }
        }
    }
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

/**
 * Apply flow that prefers uCrop's pipeline when the user has been in the crop tool, then hands
 * the result to [LastBitmapExporter] for overlay painting + encode.
 */
private suspend fun runApply(
    context: android.content.Context,
    sourceUri: Uri,
    state: LastEditorState,
    density: Float,
    uCropView: UCropView?,
    onConfirm: (Uri) -> Unit,
    onFinished: () -> Unit,
) {
    try {
        if (uCropView != null) {
            val cropped = traceAsync("imageeditor.last.crop.process") {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Uri?> { cont ->
                        uCropView.cropImageView.cropAndSaveImage(
                            Bitmap.CompressFormat.JPEG,
                            90,
                            object : BitmapCropCallback {
                                override fun onBitmapCropped(
                                    resultUri: Uri,
                                    offsetX: Int,
                                    offsetY: Int,
                                    imageWidth: Int,
                                    imageHeight: Int,
                                ) {
                                    if (cont.isActive) cont.resume(resultUri)
                                }

                                override fun onCropFailure(t: Throwable) {
                                    Timber.w(t, "uCrop failure — falling back to source")
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    }
                }
            }
            val effectiveSource = cropped ?: sourceUri
            LastBitmapExporter.export(
                context = context,
                sourceUri = effectiveSource,
                state = state,
                densityScale = density,
                skipTransformAndCrop = cropped != null,
            )
                .onSuccess(onConfirm)
                .onFailure { Timber.e(it, "Last export failed") }
        } else {
            LastBitmapExporter.export(context, sourceUri, state, density)
                .onSuccess(onConfirm)
                .onFailure { Timber.e(it, "Last export failed") }
        }
    } finally {
        onFinished()
    }
}
