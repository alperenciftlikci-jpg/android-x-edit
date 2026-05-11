/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.libraries.imageeditor.native_.BlurType
import io.element.android.libraries.imageeditor.native_.BrushType
import io.element.android.libraries.imageeditor.native_.NativePhotoEditor
import io.element.android.libraries.imageeditor.native_.TextRenderer
import io.element.android.libraries.imageeditor.native_.ui.components.TabItem
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramBottomActions
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramCropOverlay
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramDrawingCanvas
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramFilterSlider
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramRadialBlurControl
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramSimpleSlider
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramSubTabIcon
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramTabBar
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramTextDragOverlay
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramTextLayer
import io.element.android.libraries.imageeditor.native_.ui.components.TelegramToolPill
import io.element.android.libraries.imageeditor.native_.ui.state.TextFrameType
import io.element.android.libraries.imageeditor.native_.ui.state.PhotoEditorProState
import io.element.android.libraries.imageeditor.native_.ui.state.rememberPhotoEditorProState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private val TelegramAccent = Color(0xFF50A8EB)
private val TelegramBackground = Color(0xFF0F0F0F)
private val TelegramSecondary = Color(0xFF1C1C1E)
private val TelegramYellow = Color(0xFFE5BB3B)

/**
 * Telegram-style photo editor with all 7 tabs (Tune, Effects, Blur, Crop, Paint, Text, Curves).
 * Each tab presents its sub-controls in the bottom panel; the live preview at the top runs the
 * full native pipeline (filter chain → paint composite → text composite → crop) — slider drag
 * → C++ → glReadPixels → Compose Image → recompose, debounced via `collectLatest`.
 */
@Composable
fun PhotoEditorProScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = rememberPhotoEditorProState()
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var sourceLoadFailed by remember { mutableStateOf(false) }

    val nativeEditor = remember { NativePhotoEditor() }
    androidx.compose.runtime.DisposableEffect(nativeEditor) {
        onDispose { nativeEditor.close() }
    }

    LaunchedEffect(sourceUri) {
        Timber.d("PhotoEditorProScreen: loading source uri=%s", sourceUri)
        val bm = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(sourceUri)?.use {
                    val opts = BitmapFactory.Options().apply {
                        // Force ARGB_8888 explicitly. Defaults can change across API levels and
                        // some ContentProvider implementations return RGB_565.
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = false
                    }
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (t: Throwable) {
                Timber.e(t, "PhotoEditorProScreen: failed to decode source")
                null
            }
        }
        if (bm == null) {
            Timber.e("PhotoEditorProScreen: source decode returned null")
            sourceLoadFailed = true
            return@LaunchedEffect
        }
        // Downsample large photos to a preview-friendly resolution before handing off to
        // the native editor. The filter chain ping-pongs FBOs at source resolution; on a
        // typical 4 000 × 3 000 phone photo, each FBO is 48 MB and per-pass time can hit
        // 200 ms on mid-tier GPUs — that's where the "slider update takes 1–2 s" came
        // from. Capping at ~1600 px keeps everything sharp on-screen (most preview canvases
        // are 1080-wide anyway) while making each render an order of magnitude cheaper.
        val previewBm = withContext(Dispatchers.IO) {
            downsampleForPreview(bm, maxDimension = 1600)
        }
        sourceBitmap = previewBm
        val ok = withContext(Dispatchers.IO) { nativeEditor.setSource(previewBm) }
        if (!ok) {
            Timber.e("PhotoEditorProScreen: nativeEditor.setSource failed")
            sourceLoadFailed = true
        } else {
            state.scaleBrushToSource(previewBm.width, previewBm.height)
            state.sourceWidth = previewBm.width
            state.sourceHeight = previewBm.height
            previewBitmap = previewBm.asImageBitmap()
        }
    }

    // Re-render preview whenever any state slice changes — including the paint stroke tick,
    // which is bumped on every pointer sample during drawing. `collectLatest` cancels stale
    // renders so the user always sees the freshest stroke / slider / crop, never trailing
    // frames. Without `paintStrokeTick` in the key, paint strokes were only visible after
    // the user lifted their finger (commit), instead of building up live like Telegram does.
    // Mutable holder reused across renders — allocating a fresh Bitmap per render was a
    // ~10 MB churn at preview resolution and the GC pauses showed up as visible hitches.
    // We swap to a new bitmap only when the cropped output size actually changes (e.g. on
    // tab switch or aspect chip), otherwise we render in place.
    val previewBitmapCacheRef = remember { object { var bitmap: Bitmap? = null } }
    LaunchedEffect(sourceBitmap) {
        val src = sourceBitmap ?: return@LaunchedEffect
        snapshotFlow {
            PreviewKey(
                params = state.params,
                crop = state.crop,
                paintCommittedSize = state.paintStrokesCommitted.size,
                paintTick = state.paintStrokeTick,
                textCount = state.textItems.size,
                textTick = state.textTick,
                inCropTab = state.selectedTab == PhotoEditorProState.Tab.Crop,
            )
        }.collectLatest { key ->
            val out = withContext(Dispatchers.IO) {
                nativeEditor.setFilterParams(state.params)
                val effectiveCrop = if (key.inCropTab) {
                    state.crop.copy(x = 0f, y = 0f, w = 1f, h = 1f)
                } else {
                    state.crop
                }
                nativeEditor.setCropParams(effectiveCrop)
                val (cw, ch) = nativeEditor.croppedOutputSize()
                if (cw <= 0 || ch <= 0) return@withContext null
                val cached = previewBitmapCacheRef.bitmap
                val dst = if (cached != null && cached.width == cw && cached.height == ch &&
                              !cached.isRecycled) {
                    cached
                } else {
                    cached?.recycle()
                    val fresh = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                    previewBitmapCacheRef.bitmap = fresh
                    fresh
                }
                if (nativeEditor.exportTo(dst)) dst else null
            }
            if (out != null) previewBitmap = out.asImageBitmap()
        }
    }

    // While the user is on the Text tab editing entities, text rendering lives entirely in
    // the Compose layer (TelegramTextLayer with one BasicTextField per entity). That mirrors
    // Telegram's pattern: live EditTexts on top of the photo, no native bitmap round-trip
    // per keystroke. The native side ONLY composites text at export time. When the user
    // commits and leaves the Text tab we sync the committed items into native so the photo
    // preview on Tune / Effects / etc. reflects the typed text.
    LaunchedEffect(sourceBitmap) {
        val src = sourceBitmap ?: return@LaunchedEffect
        snapshotFlow {
            // Sync to native only when not in Text tab — during text editing the Compose
            // overlay is the authoritative renderer, so a native composite would cause a
            // double image. After the user leaves Text tab, snapshot all committed items.
            Triple(state.selectedTab, state.textItems.toList(), state.textTick)
        }.collectLatest { (tab, items, _) ->
            if (tab == PhotoEditorProState.Tab.Text) {
                // Clear native text composite while editing.
                withContext(Dispatchers.IO) { nativeEditor.clearText() }
                return@collectLatest
            }
            withContext(Dispatchers.IO) {
                nativeEditor.clearText()
                items.forEach { item ->
                    if (item.text.isBlank()) return@forEach
                    val bm = TextRenderer.render(
                        text = item.text,
                        fontSizePx = item.fontSizePx,
                        colorArgb = item.colorArgb,
                    )
                    nativeEditor.upsertTextItem(
                        id = item.id,
                        bitmap = bm,
                        x = item.centerX * src.width - bm.width * item.scale / 2f,
                        y = item.centerY * src.height - bm.height * item.scale / 2f,
                        scale = item.scale,
                        rotationRad = item.rotationRad,
                    )
                }
            }
            state.textTick++
        }
    }

    BackHandler(enabled = !isExporting, onBack = onCancel)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TelegramBackground)
            .systemBarsPadding(),
    ) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Apply runs the export pipeline and replaces the source URI on success. Surfaced as a
        // local lambda so both the top bar (paint-tab undo neighbour) and the bottom DONE row
        // can fire the same flow without code duplication.
        val triggerApply: () -> Unit = {
            if (!isExporting) {
                if (sourceLoadFailed || !nativeEditor.isReady) {
                    applyError = "Editor failed to initialise. Please cancel and retry."
                } else {
                    isExporting = true
                    applyError = null
                    scope.launch {
                        val outcome = runApply(
                            context, nativeEditor, sourceBitmap,
                            textItems = state.textItems.toList(),
                        )
                        isExporting = false
                        when (outcome) {
                            is ApplyOutcome.Success -> onConfirm(outcome.uri)
                            is ApplyOutcome.Error   -> applyError = outcome.message
                        }
                    }
                }
            }
        }

        // Top bar — Telegram photo editor mirror. The big ✓ on the right is the EXIT-AND-
        // APPLY action: it bakes the final pixel buffer and dismisses the editor. The bottom
        // DONE row (below the tab bar) only "commits" the active sub-edit (e.g. closes the
        // text input) without leaving the editor — same as Telegram's tool/action separation.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selectedTab == PhotoEditorProState.Tab.Paint) {
                TopBarIconButton(Icons.Filled.Undo, "Undo", Color.White,
                    onClick = { nativeEditor.undoPaint() })
            }
            Spacer(Modifier.weight(1f))
            TopBarIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Apply & close",
                tint = TelegramAccent,
                onClick = triggerApply,
            )
        }

        // Preview area + per-tab overlays.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            previewBitmap?.let { bm ->
                Image(
                    bitmap = bm,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
            // Tab-specific overlays.
            when (state.selectedTab) {
                PhotoEditorProState.Tab.Blur -> {
                    if (state.params.blur.type != BlurType.Off) {
                        TelegramRadialBlurControl(
                            type = state.params.blur.type,
                            centerX = state.params.blur.centerX,
                            centerY = state.params.blur.centerY,
                            innerRadius = state.params.blur.innerRadius,
                            outerRadius = state.params.blur.outerRadius,
                            angleRadians = state.params.blur.angleRadians,
                            onCenterChange = state::setBlurCenter,
                            onInnerRadiusChange = state::setBlurInnerRadius,
                            onOuterRadiusChange = state::setBlurOuterRadius,
                            onAngleChange = state::setBlurAngle,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    }
                }
                PhotoEditorProState.Tab.Crop -> {
                    val src = sourceBitmap
                    // After a 90° / 270° rotation the displayed image swaps width/height —
                    // we must hand the overlay the rotated dimensions so its letterbox math
                    // (centring + extent) lines up with the on-screen bitmap.
                    val rotatedOdd = state.crop.rotation90 % 2 == 1
                    val sw = if (rotatedOdd) src?.height ?: 1 else src?.width ?: 1
                    val sh = if (rotatedOdd) src?.width ?: 1 else src?.height ?: 1
                    TelegramCropOverlay(
                        rectX = state.crop.x, rectY = state.crop.y,
                        rectW = state.crop.w, rectH = state.crop.h,
                        sourceWidth = sw,
                        sourceHeight = sh,
                        aspectLocked = state.cropAspectLocked,
                        onRectChange = state::setCropRect,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
                PhotoEditorProState.Tab.Paint -> {
                    val src = sourceBitmap
                    if (src != null) {
                        TelegramDrawingCanvas(
                            enabled = true,
                            sourceWidth = src.width,
                            sourceHeight = src.height,
                            onStrokeBegin = { x, y, p ->
                                nativeEditor.beginStroke(state.brush, x, y, p)
                                state.paintStrokeTick++   // kick off live preview re-renders
                            },
                            onStrokeExtend = { x, y, p ->
                                nativeEditor.extendStroke(x, y, p)
                                // Bump per pointer sample so the preview snapshot flow fires
                                // and the user watches their stroke build up live, instead of
                                // only seeing it after they lift their finger.
                                state.paintStrokeTick++
                            },
                            onStrokeEnd = {
                                nativeEditor.endStroke()
                                state.paintStrokesCommitted.add(System.nanoTime())
                            },
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    }
                }
                PhotoEditorProState.Tab.Text -> {
                    // Auto-create the first text item on tab entry — Telegram immediately
                    // pops the keyboard up so the user starts typing without an extra tap.
                    LaunchedEffect(Unit) {
                        if (state.textItems.isEmpty()) {
                            val id = state.beginNewTextItem()
                            state.textItems.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                                ?.let { idx ->
                                    state.textItems[idx] = state.textItems[idx].copy(
                                        fontSizePx = defaultTextSizePxFor(sourceBitmap),
                                    )
                                }
                            state.editingTextId = id
                            state.isTextEditing = true
                        }
                    }
                    TelegramTextLayer(
                        items = state.textItems,
                        selectedId = state.editingTextId,
                        isEditing = state.isTextEditing,
                        onSelect = { newId ->
                            state.editingTextId = newId
                            if (newId == null) state.isTextEditing = false
                        },
                        onBeginEditing = { id ->
                            state.editingTextId = id
                            state.isTextEditing = true
                        },
                        onEndEditing = { state.isTextEditing = false },
                        onItemUpdate = { id, transform ->
                            state.updateTextItem(id, transform)
                        },
                        onTextChange = { id, newText ->
                            state.updateTextItem(id) { copy(text = newText) }
                        },
                        // Hand source dims to the layer so position + font size live in
                        // source-pixel space (= same space as native export). Without this
                        // the edit preview and the final JPEG showed text at different
                        // proportional sizes by the canvas-to-image scale factor.
                        sourceWidth = sourceBitmap?.width ?: 1,
                        sourceHeight = sourceBitmap?.height ?: 1,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
                else -> Unit
            }
        }

        // Active tab's sub-controls.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TelegramBackground)
                .padding(vertical = 4.dp),
        ) {
            when (state.selectedTab) {
                PhotoEditorProState.Tab.Tune    -> TuneTabContent(state)
                PhotoEditorProState.Tab.Effects -> EffectsTabContent(state)
                PhotoEditorProState.Tab.Blur    -> BlurTabContent(state)
                PhotoEditorProState.Tab.Crop    -> CropTabContent(state)
                PhotoEditorProState.Tab.Paint   -> PaintTabContent(state)
                PhotoEditorProState.Tab.Text    -> TextTabContent(state)
            }
        }

        // Bottom action row — CANCEL exits without applying. DONE *commits the current
        // sub-edit* (e.g. closes the text input, deselects entity) but stays in the editor
        // so the user can switch tabs or refine further. The ✓ at the TOP-RIGHT is the
        // exit-and-apply button. Mirrors Telegram's two-level commit pattern.
        TelegramBottomActions(
            cancelLabel = "CANCEL",
            doneLabel = "DONE",
            onCancel = onCancel,
            onDone = {
                when (state.selectedTab) {
                    PhotoEditorProState.Tab.Text -> {
                        // Close the keyboard and drop the selection — Telegram's
                        // TextPaintView.endEditing() equivalent.
                        state.editingTextId = null
                        state.isTextEditing = false
                    }
                    PhotoEditorProState.Tab.Paint -> {
                        // Strokes auto-commit on pointer-up; nothing more to do here.
                    }
                    PhotoEditorProState.Tab.Crop, PhotoEditorProState.Tab.Tune,
                    PhotoEditorProState.Tab.Effects, PhotoEditorProState.Tab.Blur -> {
                        // No sub-modal state — the live preview already reflects every change.
                    }
                }
            },
            doneEnabled = !isExporting && nativeEditor.isReady && !sourceLoadFailed,
            accentColor = TelegramAccent,
        )

        // Tab bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TelegramSecondary)
                .padding(vertical = 4.dp),
        ) {
            TelegramTabBar(
                tabs = listOf(
                    TabItem(PhotoEditorProState.Tab.Tune,    "Tune",    Icons.Filled.Tune),
                    TabItem(PhotoEditorProState.Tab.Effects, "Effects", Icons.Filled.AutoFixHigh),
                    TabItem(PhotoEditorProState.Tab.Blur,    "Blur",    Icons.Filled.Lens),
                    TabItem(PhotoEditorProState.Tab.Crop,    "Crop",    Icons.Filled.Crop),
                    TabItem(PhotoEditorProState.Tab.Paint,   "Paint",   Icons.Filled.Brush),
                    TabItem(PhotoEditorProState.Tab.Text,    "Text",    Icons.Filled.TextFields),
                ),
                selected = state.selectedTab,
                onSelect = { state.selectedTab = it },
                accentColor = TelegramAccent,
            )
        }
        } // end Column

        // Loading overlay — drawn ON TOP of the editor so users see immediate feedback when
        // they tap Apply. Previously this was a sibling of the Column (not stacked), so it
        // never rendered visibly; users tapped ✓ and saw nothing happen.
        AnimatedVisibility(
            visible = isExporting,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(enabled = false, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TelegramAccent)
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        text = "Exporting…",
                        style = TextStyle(color = Color.White, fontSize = 13.sp),
                    )
                }
            }
        }

        applyError?.let { msg ->
            ErrorDialog(message = msg, onDismiss = { applyError = null })
        }

        if (sourceLoadFailed) {
            ErrorDialog(
                message = "Could not open this image. Try a different photo.",
                onDismiss = onCancel,
            )
        }
    } // end outer Box
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                BasicText(
                    text = "OK",
                    style = TextStyle(color = TelegramAccent, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium),
                )
            }
        },
        title = { BasicText(text = "Editor", style = TextStyle(color = Color.White,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) },
        text = { BasicText(text = message, style = TextStyle(color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp)) },
        containerColor = TelegramSecondary,
    )
}

@Composable
private fun TopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun TuneTabContent(state: PhotoEditorProState) {
    // Telegram splits its photo-tune controls into "Light" (tone-axis sliders) and "Color"
    // (saturation/warmth-axis sliders). Keeping all eight on screen at once was visually
    // crowded, so we sub-tab here: only the active group's sliders are shown, the user
    // switches via the pill row at the top.
    var subTab by remember { mutableStateOf(TuneSubTab.Light) }
    val p = state.params
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TuneSubTabPill("Light", subTab == TuneSubTab.Light) { subTab = TuneSubTab.Light }
            TuneSubTabPill("Color", subTab == TuneSubTab.Color) { subTab = TuneSubTab.Color }
        }
        when (subTab) {
            TuneSubTab.Light -> Column {
                TelegramSimpleSlider("Exposure",   p.exposure,   -2f..2f, state::setExposure,   defaultValue = 0f, accentColor = TelegramAccent)
                TelegramSimpleSlider("Brightness", p.brightness, -1f..1f, state::setBrightness, defaultValue = 0f, accentColor = TelegramAccent)
                TelegramSimpleSlider("Contrast",   p.contrast,    0f..2f, state::setContrast,   defaultValue = 1f, accentColor = TelegramAccent)
                TelegramSimpleSlider("Highlights", p.highlights, -1f..1f, state::setHighlights, defaultValue = 0f, accentColor = TelegramAccent)
                TelegramSimpleSlider("Shadows",    p.shadows,    -1f..1f, state::setShadows,    defaultValue = 0f, accentColor = TelegramAccent)
            }
            TuneSubTab.Color -> Column {
                TelegramSimpleSlider("Saturation", p.saturation,  0f..2f, state::setSaturation, defaultValue = 1f, accentColor = TelegramAccent)
                TelegramSimpleSlider("Warmth",     p.warmth,     -1f..1f, state::setWarmth,     defaultValue = 0f, accentColor = TelegramAccent)
                TelegramSimpleSlider("Fade",       p.fade,        0f..1f, state::setFade,       defaultValue = 0f, accentColor = TelegramAccent)
            }
        }
    }
}

private enum class TuneSubTab { Light, Color }

@Composable
private fun TuneSubTabPill(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) TelegramAccent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (isSelected) TelegramAccent else Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            ),
        )
    }
}

@Composable
private fun EffectsTabContent(state: PhotoEditorProState) {
    val p = state.params
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        TelegramSimpleSlider("Vignette", p.vignette, 0f..1f, state::setVignette, defaultValue = 0f, accentColor = TelegramAccent)
        TelegramSimpleSlider("Grain",    p.grain,    0f..1f, state::setGrain,    defaultValue = 0f, accentColor = TelegramAccent)
        TelegramSimpleSlider("Sharpen",  p.sharpen,  0f..1f, state::setSharpen,  defaultValue = 0f, accentColor = TelegramAccent)
    }
}

@Composable
private fun BlurTabContent(state: PhotoEditorProState) {
    val p = state.params
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModePill("Off",    p.blur.type == BlurType.Off)    { state.setBlurType(BlurType.Off) }
            ModePill("Radial", p.blur.type == BlurType.Radial) { state.setBlurType(BlurType.Radial) }
            ModePill("Linear", p.blur.type == BlurType.Linear) { state.setBlurType(BlurType.Linear) }
        }
        TelegramSimpleSlider(
            label = "Strength",
            value = p.blur.strength,
            range = 0f..1f,
            onValueChange = state::setBlurStrength,
            defaultValue = 0.6f,
            accentColor = TelegramAccent,
        )
    }
}

@Composable
private fun CropTabContent(state: PhotoEditorProState) {
    val aspectChips = remember {
        listOf<Pair<String, Float?>>(
            "Free"    to null,
            "1:1"     to 1f,
            "4:5"     to 4f / 5f,
            "5:4"     to 5f / 4f,
            "4:3"     to 4f / 3f,
            "3:4"     to 3f / 4f,
            "16:9"    to 16f / 9f,
            "9:16"    to 9f / 16f,
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            aspectChips.forEach { (label, ratio) ->
                AspectChip(
                    label = label,
                    isSelected = state.cropAspectLocked == ratio ||
                                 (state.cropAspectLocked == null && ratio == null),
                    onClick = { state.setCropAspect(ratio) },
                )
            }
        }
        // Centred angle readout — Telegram crop screen shows the live degree above the slider.
        Spacer(Modifier.height(6.dp))
        BasicText(
            text = "%.1f°".format(state.crop.freeAngle),
            style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                fontWeight = FontWeight.Medium),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        // Free-angle slider — left flip icon, slider, right rotate icon, all in one row to
        // match the screenshot layout exactly.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactCropIcon(Icons.Filled.Refresh, "Mirror", state::toggleMirrorH)
            Box(modifier = Modifier.weight(1f)) {
                TelegramSimpleSlider(
                    label = "",
                    value = state.crop.freeAngle,
                    range = -45f..45f,
                    onValueChange = state::setFreeAngle,
                    defaultValue = 0f,
                    accentColor = TelegramAccent,
                    labelWidth = 0.dp,
                )
            }
            CompactCropIcon(Icons.Filled.Rotate90DegreesCw, "Rotate", state::rotate90)
        }
    }
}

@Composable
private fun CompactCropIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PaintTabContent(state: PhotoEditorProState) {
    val palette = listOf(
        Color.White, Color(0xFFEF5350), Color(0xFFFF9800), Color(0xFFFFEB3B),
        Color(0xFF66BB6A), Color(0xFF42A5F5), Color(0xFFAB47BC), Color.Black,
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        // Telegram-style rounded pill containing colour palette + brush type chips. The user
        // sees a single dark capsule of tools, exactly like the upstream draw screen.
        TelegramToolPill(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                palette.forEach { c ->
                    val isSelected = c.red == state.brush.r &&
                                     c.green == state.brush.g &&
                                     c.blue == state.brush.b
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 26.dp else 22.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(2.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
                            .clickable {
                                state.setBrushColor(c.red, c.green, c.blue, c.alpha)
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Brush type pill row.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            BrushTypeChip("Pen",    state.brush.type == BrushType.Pen)    { state.setBrushType(BrushType.Pen) }
            BrushTypeChip("Marker", state.brush.type == BrushType.Marker) { state.setBrushType(BrushType.Marker) }
            BrushTypeChip("Neon",   state.brush.type == BrushType.Neon)   { state.setBrushType(BrushType.Neon) }
            BrushTypeChip("Arrow",  state.brush.type == BrushType.Arrow)  { state.setBrushType(BrushType.Arrow) }
            BrushTypeChip("Eraser", state.brush.type == BrushType.Eraser) { state.setBrushType(BrushType.Eraser) }
        }
        Spacer(Modifier.height(2.dp))
        TelegramSimpleSlider(
            label = "Size",
            value = state.brush.radiusPx,
            range = 2f..48f,
            onValueChange = state::setBrushSize,
            defaultValue = 12f,
            accentColor = TelegramAccent,
        )
    }
}

@Composable
private fun TextTabContent(state: PhotoEditorProState) {
    val editing = state.editingTextId?.let { id -> state.textItems.firstOrNull { it.id == id } }
    val palette = listOf(
        Color.White, Color.Black, Color(0xFFEF5350), Color(0xFFFF9800),
        Color(0xFFFFEB3B), Color(0xFF66BB6A), Color(0xFF42A5F5), Color(0xFFAB47BC),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        // Action row — Add new, Delete current, Done (commits). Matches Telegram's overlay
        // toolbar that appears when a text entity is selected.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            CropTransformButton(Icons.Filled.TextFields, "Add",
                onClick = {
                    val id = state.beginNewTextItem()
                    state.editingTextId = id
                    state.isTextEditing = true
                })
            if (editing != null) {
                CropTransformButton(Icons.Filled.Close, "Delete",
                    onClick = { state.removeTextItem(editing.id) })
                CropTransformButton(Icons.Filled.Check, "Done",
                    onClick = {
                        state.editingTextId = null
                        state.isTextEditing = false
                    })
            }
        }
        if (editing != null) {
            Spacer(Modifier.height(2.dp))
            // 4 frame-type chips — same set Telegram exposes in its text-options panel.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextFrameType.values().forEach { type ->
                    TextFrameTypeChip(
                        type = type,
                        isSelected = editing.frameType == type,
                        onClick = {
                            state.updateTextItem(editing.id) { copy(frameType = type) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            // Color palette.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                palette.forEach { c ->
                    val argb = c.toArgb()
                    val isSelected = editing.colorArgb == argb
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 30.dp else 26.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(2.dp, if (isSelected) Color.White else Color.Transparent,
                                    CircleShape)
                            .clickable {
                                state.updateTextItem(editing.id) { copy(colorArgb = argb) }
                            },
                    )
                }
            }
            BasicText(
                text = "Pinch to resize · drag to move · twist to rotate",
                style = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun TextFrameTypeChip(type: TextFrameType, isSelected: Boolean, onClick: () -> Unit) {
    // Each chip previews its visual style — solid square, semi-transparent, outlined, plain.
    val label = when (type) {
        TextFrameType.Plain -> "A"
        TextFrameType.Solid -> "A"
        TextFrameType.Semi -> "A"
        TextFrameType.Outline -> "A"
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when (type) {
                    TextFrameType.Solid -> Color.White
                    TextFrameType.Semi -> Color.White.copy(alpha = 0.45f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (type == TextFrameType.Outline) 2.dp else if (isSelected) 1.5.dp else 0.dp,
                color = when {
                    type == TextFrameType.Outline -> Color.White
                    isSelected -> TelegramAccent
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = when (type) {
                    TextFrameType.Solid -> Color.Black
                    TextFrameType.Semi -> Color.Black
                    else -> Color.White
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/** Default font size for a freshly-created text entity. Matches Telegram's
 *  `paintingSize.width / 9` formula in `LPhotoPaintView.createText` — produces
 *  identical proportional sizes whether the image is 600 px or 4 000 px wide,
 *  because the live editor scales source-pixel sizes to the on-screen canvas. */
private fun defaultTextSizePxFor(bm: android.graphics.Bitmap?): Float {
    return ((bm?.width ?: 576) / 9f).coerceAtLeast(24f)
}

/** Downscale [src] so neither dimension exceeds [maxDimension], preserving aspect ratio.
 *  Returns [src] unchanged if it already fits. The native filter chain ping-pongs FBOs
 *  at source resolution, so capping here makes every subsequent render an order of
 *  magnitude cheaper without affecting on-screen sharpness (most preview canvases
 *  are 1080 px wide). Telegram's editor takes the same approach — `paintingSize` is
 *  derived from a sized-down version of the source, not the camera-native raw. */
private fun downsampleForPreview(src: android.graphics.Bitmap, maxDimension: Int): Bitmap {
    val w = src.width
    val h = src.height
    val largest = maxOf(w, h)
    if (largest <= maxDimension) return src
    val scale = maxDimension.toFloat() / largest
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    Timber.d("downsampleForPreview: %dx%d -> %dx%d", w, h, newW, newH)
    val out = Bitmap.createScaledBitmap(src, newW, newH, true)
    if (out !== src) src.recycle()
    return out
}

/**
 * Centred floating TextField overlay used by the Text tab. Auto-focuses on first composition
 * so the soft keyboard pops up immediately — same UX as Telegram's photo editor: tap "Text"
 * tab → cursor lands in the middle of the photo → start typing → text appears live.
 *
 * Visually we keep this field largely transparent because the *real* rendered text is the
 * bitmap composited by the native side. The TextField only needs to capture key input and
 * show a cursor / placeholder. When the value matches what the native renderer is showing
 * the user perceives a single object.
 */
@Composable
private fun CenteredTextEditor(
    text: String,
    fontSizePx: Float,
    colorArgb: Int,
    onTextChange: (String) -> Unit,
    onSizeScale: (Float) -> Unit,
    onDoneIme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val density = LocalDensity.current
    val sizeSp = with(density) { fontSizePx.toDp().toSp() }
    // Two-finger pinch resizes the text. detectTransformGestures only fires once two
    // pointers are down, so single-finger taps fall through to the BasicTextField below
    // for cursor placement / focus. Telegram's hand-resize gesture is the same.
    // Manual two-finger pinch detector. We hand-roll this because
    // `detectTransformGestures` from androidx.compose.foundation.gestures isn't on the
    // foundation dependency this module pulls in (Compose split happens between
    // foundation-layout and foundation-gestures across versions; we want the algorithm
    // here regardless of which artefact is on the classpath). Behaviour: track distance
    // between two pointers each frame, emit incremental zoom = curDist / prevDist.
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var prevDist: Float? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val active = event.changes.filter { it.pressed }
                    if (active.size < 2) {
                        if (active.isEmpty()) return@awaitEachGesture
                        prevDist = null
                        continue
                    }
                    val a = active[0].position
                    val b = active[1].position
                    val dx = a.x - b.x
                    val dy = a.y - b.y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val previous = prevDist
                    if (previous != null && previous > 1f && dist > 1f) {
                        val zoom = dist / previous
                        if (zoom != 1f) onSizeScale(zoom)
                    }
                    prevDist = dist
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = false,
            cursorBrush = SolidColor(TelegramAccent),
            textStyle = TextStyle(
                // Render with the editing color, but mostly transparent — the native bitmap
                // composited onto the canvas is what the user actually sees. Keeping the
                // TextField's text near-transparent avoids a doubled-up appearance, while
                // keeping it slightly visible while empty so the user knows where to tap.
                color = Color(colorArgb).copy(alpha = if (text.isEmpty()) 0.7f else 0f),
                fontSize = sizeSp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onDoneIme() },
            ),
            modifier = Modifier.focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        BasicText(
                            text = "Type…",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = sizeSp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            ),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ModePill(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) TelegramAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun AspectChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) TelegramYellow.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, if (isSelected) TelegramYellow else Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (isSelected) TelegramYellow else Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun BrushTypeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) TelegramAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun CropTransformButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = label,
            style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp),
        )
    }
}

// Fields whose change should trigger a preview re-render. We pack them into a data class so
// snapshotFlow's distinct-until-changed comparison fires on identity equality of immutable
// fields — much cheaper than re-running a deep comparison on every recomposition.
private data class PreviewKey(
    val params: io.element.android.libraries.imageeditor.native_.FilterParams,
    val crop: io.element.android.libraries.imageeditor.native_.CropParams,
    val paintCommittedSize: Int,
    val paintTick: Long,
    val textCount: Int,
    val textTick: Long,
    val inCropTab: Boolean,
)

private sealed class ApplyOutcome {
    data class Success(val uri: Uri) : ApplyOutcome()
    data class Error(val message: String) : ApplyOutcome()
}

private suspend fun runApply(
    context: android.content.Context,
    nativeEditor: NativePhotoEditor,
    sourceBitmap: Bitmap?,
    textItems: List<io.element.android.libraries.imageeditor.native_.ui.state.TextItemEdit>,
): ApplyOutcome = withContext(Dispatchers.IO) {
    val src = sourceBitmap
        ?: return@withContext ApplyOutcome.Error("Source image not loaded.")
    // Telegram bakes text entities into the final pixel buffer only at export. While the
    // user was on the Text tab the Compose layer owned rendering, so native text composite
    // was cleared. Now we push every committed item into native right before exportTo so
    // the JPEG has the typed text on it.
    nativeEditor.clearText()
    textItems.forEach { item ->
        if (item.text.isBlank()) return@forEach
        val bm = TextRenderer.render(
            text = item.text,
            fontSizePx = item.fontSizePx,
            colorArgb = item.colorArgb,
        )
        nativeEditor.upsertTextItem(
            id = item.id,
            bitmap = bm,
            x = item.centerX * src.width - bm.width * item.scale / 2f,
            y = item.centerY * src.height - bm.height * item.scale / 2f,
            scale = item.scale,
            rotationRad = item.rotationRad,
        )
    }
    val (cw, ch) = nativeEditor.croppedOutputSize().let {
        if (it.first <= 0 || it.second <= 0) src.width to src.height else it
    }
    Timber.d("runApply: exporting at %dx%d", cw, ch)
    val dst = try {
        Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "runApply: OOM creating destination bitmap (%dx%d)", cw, ch)
        return@withContext ApplyOutcome.Error("Image too large to export.")
    }
    if (!nativeEditor.exportTo(dst)) {
        dst.recycle()
        return@withContext ApplyOutcome.Error("Export failed. Check logcat for native errors.")
    }
    val outFile = java.io.File(context.cacheDir, "photoedit-pro-${System.currentTimeMillis()}.jpg")
    try {
        java.io.FileOutputStream(outFile).use { out ->
            if (!dst.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                return@withContext ApplyOutcome.Error("Failed to encode JPEG.")
            }
        }
    } catch (t: Throwable) {
        Timber.e(t, "runApply: failed writing JPEG")
        return@withContext ApplyOutcome.Error("Failed to write image: ${t.message}")
    } finally {
        dst.recycle()
    }
    Timber.d("runApply: wrote %s (%d bytes)", outFile.absolutePath, outFile.length())
    ApplyOutcome.Success(Uri.fromFile(outFile))
}
