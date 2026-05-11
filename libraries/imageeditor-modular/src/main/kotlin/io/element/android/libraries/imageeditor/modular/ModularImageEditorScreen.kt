/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.imageeditor.modular

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
import android.graphics.Bitmap
import com.yalantis.ucrop.callback.BitmapCropCallback
import com.yalantis.ucrop.view.UCropView
import io.element.android.libraries.core.perf.BitmapDecoders
import io.element.android.libraries.core.perf.traceAsync
import io.element.android.libraries.imageeditor.modular.render.ModularBitmapExporter
import io.element.android.libraries.imageeditor.modular.tools.crop.ModularUCropTool
import io.element.android.libraries.imageeditor.modular.tools.draw.ModularInkDrawTool
import io.element.android.libraries.imageeditor.modular.tools.text.ModularTextOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * "Best of breed" alternative to [io.element.android.libraries.imageeditor.ImageEditorScreen]:
 *
 * - **Crop**  → uCrop's `UCropView` embedded via `AndroidView`
 * - **Draw**  → Jetpack Ink's `InProgressStrokes` with `CanvasStrokeRenderer` for committed strokes
 * - **Text**  → our own Compose overlay (same logic as the baseline)
 *
 * No filter tool — neither implementation has one.
 *
 * All trace section names are under the `imageeditor.modular.*` prefix so the perf dashboard shows
 * them alphabetically next to the baseline `imageeditor.*` cards. Same scenario, two cards each →
 * direct comparison.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModularImageEditorScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    config: ModularImageEditorConfig = ModularImageEditorConfig(),
) {
    val state = rememberModularEditorState(config)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    // Reference to uCrop's view, captured when the crop tool is composed. We use it on Apply to
    // ask uCrop to bake its current crop+rotate gesture state into a temp file, then feed that
    // already-transformed file to the modular exporter (which then only paints overlays + encodes).
    var uCropViewRef by remember { mutableStateOf<UCropView?>(null) }
    var hasCroppedOnce by remember { mutableStateOf(false) }

    BackHandler(enabled = !isExporting) {
        if (state.activeTool != ModularEditorTool.None) {
            state.selectTool(ModularEditorTool.None)
        } else {
            onCancel()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        topBar = {
            TopAppBar(
                title = { Text("Edit (modular)") },
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
            ModularBottomToolbar(
                state = state,
                onAddDefaultText = {
                    val sample = ModularTextItem(
                        text = "Sample text",
                        position = Offset(0.3f, 0.4f),
                        color = state.drawColor,
                    )
                    state.addText(sample)
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
}

@Composable
private fun ImageStage(
    sourceUri: Uri,
    state: ModularEditorState,
    onUCropViewReady: (UCropView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Pre-decode the source bitmap once. We render via Compose `Image` with this bitmap rather
    // than `AsyncImage`, because Jetpack Ink's `InProgressStrokes` uses an underlying SurfaceView
    // whose hole-punching trips up async/lazy-loaded image layers. We host the preview bitmap
    // through an AndroidView ImageView (sibling View to the SurfaceView, standard View buffer
    // compositing) instead of a Compose `Image` — the previous `CompositingStrategy.Offscreen`
    // approach worked on most devices but on certain GPU/driver combinations the SurfaceView
    // hole-punch still corrupted the Compose RenderNode buffer underneath, producing the
    // "fragmented image scattered across the canvas" artifact the user reported.
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
            // Crop tool replaces the underlying image stack while active.
            if (state.activeTool == ModularEditorTool.Crop) {
                ModularUCropTool(
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
                // Image rendered via AndroidView + ImageView — sibling View hierarchy to
                // InProgressStrokes' SurfaceView. Standard View buffer compositing respects the
                // SurfaceView z-order without the GPU buffer corruption we hit with Compose
                // `Image` + `CompositingStrategy.Offscreen` on some devices/drivers.
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
                                scaleType = ImageView.ScaleType.FIT_XY
                                setImageBitmap(bm)
                            }
                        },
                        update = { iv ->
                            if (iv.drawable !is android.graphics.drawable.BitmapDrawable ||
                                (iv.drawable as android.graphics.drawable.BitmapDrawable).bitmap !== bm
                            ) {
                                iv.setImageBitmap(bm)
                            }
                        },
                    )
                }

                ModularInkDrawTool(
                    brush = state.drawBrush,
                    committedStrokes = state.inkStrokes,
                    enabled = state.activeTool == ModularEditorTool.Draw ||
                        state.activeTool == ModularEditorTool.Highlighter,
                    onStrokeFinished = state::addStroke,
                    modifier = Modifier.fillMaxSize(),
                )

                ModularTextOverlay(
                    items = state.textItems,
                    enabled = state.activeTool == ModularEditorTool.Text,
                    onItemMoved = state::updateText,
                    // No text editor dialog in this scaffold — tap to remove the text item.
                    onTextTap = { state.removeText(it.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ModularBottomToolbar(
    state: ModularEditorState,
    onAddDefaultText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Secondary row — brush color + stroke width — only visible while a drawing tool is active.
        // Jetpack Ink supports per-stroke colour/size via Brush.createWithColorIntArgb so we can
        // hand it a fresh Brush whenever the user picks a new swatch.
        if (state.activeTool == ModularEditorTool.Draw || state.activeTool == ModularEditorTool.Highlighter) {
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
            selected = state.activeTool == ModularEditorTool.Crop,
            onClick = {
                state.selectTool(
                    if (state.activeTool == ModularEditorTool.Crop) ModularEditorTool.None
                    else ModularEditorTool.Crop,
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
            selected = state.activeTool == ModularEditorTool.Draw,
            onClick = {
                state.selectTool(
                    if (state.activeTool == ModularEditorTool.Draw) ModularEditorTool.None
                    else ModularEditorTool.Draw,
                )
            },
        )
        ToolButton(
            icon = Icons.Filled.TextFields,
            label = "Text",
            selected = state.activeTool == ModularEditorTool.Text,
            onClick = {
                state.selectTool(ModularEditorTool.Text)
                onAddDefaultText()
            },
        )
        ToolButton(
            icon = Icons.Filled.Delete,
            label = "Undo",
            selected = false,
            onClick = { state.undoLastStroke() },
        )
    }
    } // end Column
}

@Composable
private fun DrawSecondaryRow(state: ModularEditorState) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // Color palette — Jetpack Ink rebuilds the brush via Brush.createWithColorIntArgb when
        // state.drawColor changes (see ModularEditorState.setDrawColor).
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
        // Stroke width — same Brush rebuild path; the dot size mirrors the actual width.
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
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.4f), CircleShape)
                        .clickable { state.setDrawStrokeWidth(width) },
                )
            }
        }
    }
}

/**
 * Apply flow that prefers uCrop's own cropping pipeline when the user has been in the crop tool.
 *
 * Two paths:
 *   1. **uCrop path** — `cropAndSaveImage` writes a fully transformed (rotated, scaled, cropped)
 *      bitmap to `outputUri`. We then feed that URI into [ModularBitmapExporter] with
 *      `skipTransformAndCrop = true` so the exporter only needs to paint the ink/text overlays.
 *   2. **No-crop path** — same as the baseline: source → rotate/flip → annotate → crop → encode.
 *
 * Wrapped here as a top-level `suspend` function because it contains a callback-to-suspend bridge
 * (`suspendCancellableCoroutine`) that doesn't sit naturally inside a Compose lambda.
 */
private suspend fun runApply(
    context: android.content.Context,
    sourceUri: Uri,
    state: ModularEditorState,
    density: Float,
    uCropView: UCropView?,
    onConfirm: (Uri) -> Unit,
    onFinished: () -> Unit,
) {
    try {
        if (uCropView != null) {
            // Wrap uCrop's cropAndSaveImage with our own trace so the dashboard shows the actual
            // "crop work" time as a separate metric — distinct from the user-paced .gesture trace
            // and the instant .apply / .reset state mutations. This is where the real bitmap
            // decode + transform + crop + JPEG encode happens inside uCrop's pipeline.
            val cropped = traceAsync("imageeditor.modular.crop.process") {
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
            ModularBitmapExporter.export(
                context = context,
                sourceUri = effectiveSource,
                state = state,
                densityScale = density,
                skipTransformAndCrop = cropped != null,
            )
                .onSuccess(onConfirm)
                .onFailure { Timber.e(it, "Modular export failed") }
        } else {
            ModularBitmapExporter.export(context, sourceUri, state, density)
                .onSuccess(onConfirm)
                .onFailure { Timber.e(it, "Modular export failed") }
        }
    } finally {
        onFinished()
    }
}

// Preview decoded bitmap is capped to this many pixels on the longest side. Most phone screens
// are ≤ 1440 px wide; 2048 keeps a comfortable margin for zoom/rotate without blowing native heap.
// Export still uses the full-resolution decode path (`decodeFullRes`).
private const val PREVIEW_MAX_DIM = 2048

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
