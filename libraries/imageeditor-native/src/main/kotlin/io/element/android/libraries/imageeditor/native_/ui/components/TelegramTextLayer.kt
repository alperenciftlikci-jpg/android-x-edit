/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.libraries.imageeditor.native_.ui.state.TextFrameType
import io.element.android.libraries.imageeditor.native_.ui.state.TextItemEdit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Compose port of Telegram's `TextPaintView` entity layer. Architecture mirrors
 * `LPhotoPaintView`:
 *
 *   * Each text item is a movable / scalable / rotatable Box, positioned at its
 *     normalised image-space coordinates over the photo preview.
 *   * Exactly one item is selected at a time. Selected items show a dashed accent
 *     border, identical to Telegram's `SelectionView`.
 *   * **All gestures live on the parent (non-moving) Box**, not on each entity.
 *     Per-entity pointerInput nested inside a `Modifier.offset` shifted the local
 *     coordinate system with the entity, producing a feedback loop where a single
 *     finger drag bounced the entity back-and-forth (`titriyor`). With the
 *     parent owning gestures, pointer positions stay in stable canvas-pixel space.
 *   * Hit-testing is done manually against each entity's bounding rect (centre +
 *     measured size × scale). Bounds are reported per-entity via `onSizeChanged`
 *     into a state-map.
 *   * Single-finger drag pans, two fingers scale + rotate. Tap on selected
 *     entity = re-edit (re-focus IME). Tap on different entity = swap selection.
 *     Tap on background = deselect.
 *
 * Frame types match Telegram's `currentType` field — Solid / Semi / Outline / Plain.
 * Edit-time rendering is purely Compose; native compositing happens at export.
 */
@Composable
fun TelegramTextLayer(
    items: List<TextItemEdit>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    onItemUpdate: (Int, (TextItemEdit) -> TextItemEdit) -> Unit,
    onTextChange: (Int, String) -> Unit,
    isEditing: Boolean,
    onBeginEditing: (Int) -> Unit,
    onEndEditing: () -> Unit,
    sourceWidth: Int,
    sourceHeight: Int,
    /** Current crop / rotation / mirror state. Determines the output bitmap's effective
     *  dimensions, which is what we letterbox against — without this, dragging a text
     *  entity on a 90° rotated photo lands in the wrong place because the layer thinks
     *  the photo is still landscape. */
    cropParams: io.element.android.libraries.imageeditor.native_.CropParams,
    /** When false, taps that don't hit an entity are NOT consumed — they propagate to
     *  whatever pointer-input modifier is layered below (Crop overlay, Paint canvas, etc.).
     *  Set false from non-Text tabs so the active tool's overlay still receives touches. */
    interceptBackgroundTaps: Boolean = true,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF50A8EB),
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Output dims = what's actually drawn on screen post-crop / post-rotation. Letterbox
    // math runs against THESE, not the raw source. Otherwise text entities float at
    // positions computed for an un-rotated source aspect while the photo behind them is
    // rotated → they all visibly miss the photo.
    val outDims = remember(sourceWidth, sourceHeight, cropParams) {
        CropUvMatrix.outputDims(sourceWidth, sourceHeight, cropParams)
    }
    val imageRect = remember(canvasSize, outDims) {
        computeImageDisplayRect(
            canvasSize.width.toFloat(), canvasSize.height.toFloat(),
            outDims.first, outDims.second,
        )
    }
    val sourceToScreen = if (outDims.first > 0) imageRect.width / outDims.first else 1f
    // Per-entity measured size (pre-scale, in canvas pixels). The parent uses this to do
    // hit-testing against each entity's visual extent without going through the entity's
    // own pointerInput (which would corrupt drag math).
    val entitySizes = remember { mutableStateMapOf<Int, IntSize>() }
    // Read latest state from inside the long-lived pointer-input coroutine without
    // restarting the lambda on every recomposition.
    val itemsState = rememberUpdatedState(items)
    val selectedIdState = rememberUpdatedState(selectedId)
    val imageRectState = rememberUpdatedState(imageRect)

    val interceptBgState = rememberUpdatedState(interceptBackgroundTaps)
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val currentItems = itemsState.value
                    val currentImageRect = imageRectState.value
                    // Top-most item under the touch wins selection (later in `items` =
                    // newer / drawn on top).
                    val hitItem = currentItems.lastOrNull { item ->
                        hitTestEntity(downPos, item, currentImageRect, entitySizes[item.id])
                    }

                    if (hitItem == null) {
                        // Background tap. If we're on a non-Text tab the active tool's
                        // overlay below needs the event — return WITHOUT consuming. On
                        // Text tab we wait for lift to deselect.
                        if (!interceptBgState.value) return@awaitEachGesture
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Final)
                            val change = ev.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (!moved && !change.isConsumed) onSelect(null)
                                break
                            }
                            if (!change.isConsumed) {
                                val dx = change.position.x - downPos.x
                                val dy = change.position.y - downPos.y
                                if (abs(dx) + abs(dy) > 8f) moved = true
                            }
                        }
                        return@awaitEachGesture
                    }

                    // Entity hit — drive drag / pinch / rotate from this stable coordinate
                    // frame (canvas pixels, unaffected by the entity's offset / scale).
                    down.consume()
                    val hitId = hitItem.id
                    var prevPos: Offset = downPos
                    var prevDist: Float? = null
                    var prevAngle: Float? = null
                    var moved = false

                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val active = ev.changes.filter { it.pressed }
                        if (active.isEmpty()) {
                            // Pointer-up. If the user didn't drag, treat as tap.
                            if (!moved) {
                                if (hitId == selectedIdState.value) onBeginEditing(hitId)
                                else onSelect(hitId)
                            }
                            break
                        }
                        active.forEach { it.consume() }
                        if (active.size >= 2) {
                            val a = active[0].position
                            val b = active[1].position
                            val dx = a.x - b.x
                            val dy = a.y - b.y
                            val dist = hypot(dx, dy)
                            val angle = atan2(dy, dx)
                            val pd = prevDist
                            val pa = prevAngle
                            if (pd != null && pd > 1f && dist > 1f) {
                                onItemUpdate(hitId) { cur ->
                                    cur.copy(scale = (cur.scale * dist / pd).coerceIn(0.3f, 6f))
                                }
                            }
                            if (pa != null) {
                                var da = angle - pa
                                val twoPi = (2.0 * Math.PI).toFloat()
                                if (da > Math.PI.toFloat()) da -= twoPi
                                if (da < -Math.PI.toFloat()) da += twoPi
                                onItemUpdate(hitId) { cur ->
                                    cur.copy(rotationRad = cur.rotationRad + da)
                                }
                            }
                            prevDist = dist
                            prevAngle = angle
                            moved = true
                        } else {
                            val p = active[0].position
                            val ddx = p.x - prevPos.x
                            val ddy = p.y - prevPos.y
                            if (abs(ddx) + abs(ddy) > 0.5f) {
                                val rect = imageRectState.value
                                if (rect.width > 0f && rect.height > 0f) {
                                    onItemUpdate(hitId) { cur ->
                                        cur.copy(
                                            centerX = (cur.centerX + ddx / rect.width).coerceIn(0f, 1f),
                                            centerY = (cur.centerY + ddy / rect.height).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                                moved = true
                            }
                            prevPos = p
                            prevDist = null
                            prevAngle = null
                        }
                    }
                }
            },
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
        }

        items.forEach { item ->
            key(item.id) {
                TextEntityVisual(
                    item = item,
                    isSelected = item.id == selectedId,
                    isEditing = isEditing && item.id == selectedId,
                    imageRect = imageRect,
                    sourceToScreen = sourceToScreen,
                    accentColor = accentColor,
                    onLayoutSize = { sz -> entitySizes[item.id] = sz },
                    onTextChange = { newText -> onTextChange(item.id, newText) },
                    onEndIme = onEndEditing,
                )
            }
        }
    }
}

/** Axis-aligned hit-test against an entity's visual bounds. Rotation widens the bounds to
 *  the rotated bounding rect so a tilted text item still picks up taps near its corners. */
private fun hitTestEntity(
    point: Offset,
    item: TextItemEdit,
    imageRect: ImageDisplayRect,
    size: IntSize?,
): Boolean {
    if (size == null || size.width == 0 || size.height == 0) return false
    if (imageRect.width <= 0f || imageRect.height <= 0f) return false
    val cx = imageRect.left + item.centerX * imageRect.width
    val cy = imageRect.top + item.centerY * imageRect.height
    val halfW = size.width * item.scale / 2f
    val halfH = size.height * item.scale / 2f
    // Inflate by max(halfW, halfH) for rotation tolerance (cheap, slightly over-permissive).
    val pad = maxOf(halfW, halfH) - minOf(halfW, halfH)
    val left = cx - halfW - pad
    val right = cx + halfW + pad
    val top = cy - halfH - pad
    val bottom = cy + halfH + pad
    return point.x in left..right && point.y in top..bottom
}

@Composable
private fun TextEntityVisual(
    item: TextItemEdit,
    isSelected: Boolean,
    isEditing: Boolean,
    imageRect: ImageDisplayRect,
    sourceToScreen: Float,
    accentColor: Color,
    onLayoutSize: (IntSize) -> Unit,
    onTextChange: (String) -> Unit,
    onEndIme: () -> Unit,
) {
    if (imageRect.width <= 0f || imageRect.height <= 0f) return
    var selfSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                // Position the entity's CENTRE on its (centerX, centerY) within the
                // displayed photo rect. Modifier.offset {} is a layout-phase shift —
                // the visible position follows the item state, but the parent's
                // pointerInput is a sibling-level modifier that stays in canvas-pixel
                // space (it doesn't ride along with our offset).
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        x = (imageRect.left + item.centerX * imageRect.width -
                                selfSize.width / 2f).toInt(),
                        y = (imageRect.top + item.centerY * imageRect.height -
                                selfSize.height / 2f).toInt(),
                    )
                }
                .onSizeChanged {
                    selfSize = it
                    onLayoutSize(it)
                }
                .graphicsLayer {
                    transformOrigin = TransformOrigin.Center
                    scaleX = item.scale
                    scaleY = item.scale
                    rotationZ = Math.toDegrees(item.rotationRad.toDouble()).toFloat()
                },
        ) {
            TextEntityBody(
                item = item,
                isSelected = isSelected,
                isEditing = isEditing,
                sourceToScreen = sourceToScreen,
                accentColor = accentColor,
                onTextChange = onTextChange,
                onEndIme = onEndIme,
            )
        }
    }
}

@Composable
private fun TextEntityBody(
    item: TextItemEdit,
    isSelected: Boolean,
    isEditing: Boolean,
    sourceToScreen: Float,
    accentColor: Color,
    onTextChange: (String) -> Unit,
    onEndIme: () -> Unit,
) {
    val density = LocalDensity.current
    val screenPx = item.fontSizePx * sourceToScreen
    val sizeSp = with(density) { screenPx.toDp().toSp() }
    val swatch = Color(item.colorArgb)

    val backgroundColor: Color
    val textColor: Color
    val borderColor: Color
    when (item.frameType) {
        TextFrameType.Solid -> {
            backgroundColor = swatch
            textColor = if (perceivedBrightness(swatch) >= 0.721f) Color.Black else Color.White
            borderColor = Color.Transparent
        }
        TextFrameType.Semi -> {
            backgroundColor = swatch.copy(alpha = 0.6f)
            textColor = if (perceivedBrightness(swatch) >= 0.5f) Color.Black else Color.White
            borderColor = Color.Transparent
        }
        TextFrameType.Outline -> {
            backgroundColor = Color.Transparent
            textColor = swatch
            borderColor = swatch
        }
        TextFrameType.Plain -> {
            backgroundColor = Color.Transparent
            textColor = swatch
            borderColor = Color.Transparent
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            kotlinx.coroutines.delay(50)
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .let { m ->
                if (isSelected) {
                    m.padding(8.dp)
                        .border(
                            width = 1.5.dp,
                            color = accentColor.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(8.dp)
                } else {
                    m.padding(8.dp)
                }
            }
            .let { m ->
                when (item.frameType) {
                    TextFrameType.Outline -> m
                        .clip(RoundedCornerShape(6.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                    TextFrameType.Solid, TextFrameType.Semi -> m
                        .clip(RoundedCornerShape(6.dp))
                        .background(backgroundColor)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                    TextFrameType.Plain -> m
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isEditing) {
            BasicTextField(
                value = item.text,
                onValueChange = onTextChange,
                singleLine = false,
                cursorBrush = SolidColor(textColor),
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = sizeSp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEndIme() }),
                modifier = Modifier.focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (item.text.isEmpty()) {
                            BasicText(
                                text = "Type…",
                                style = TextStyle(
                                    color = textColor.copy(alpha = 0.45f),
                                    fontSize = sizeSp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                ),
                            )
                        }
                        inner()
                    }
                },
            )
        } else {
            BasicText(
                text = item.text.ifEmpty { " " },
                style = TextStyle(
                    color = textColor,
                    fontSize = sizeSp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

private fun perceivedBrightness(c: Color): Float =
    c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f

/** The on-canvas rectangle where the photo is actually drawn under `ContentScale.Fit`.
 *  Text entities live inside this rect so they line up with the visible photo regardless
 *  of letterbox. */
internal data class ImageDisplayRect(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun computeImageDisplayRect(
    canvasW: Float, canvasH: Float, srcW: Int, srcH: Int,
): ImageDisplayRect {
    if (canvasW <= 0f || canvasH <= 0f || srcW <= 0 || srcH <= 0) {
        return ImageDisplayRect(0f, 0f, canvasW, canvasH)
    }
    val imageAspect = srcW.toFloat() / srcH.toFloat()
    val canvasAspect = canvasW / canvasH
    val dispW: Float
    val dispH: Float
    if (imageAspect > canvasAspect) {
        dispW = canvasW
        dispH = canvasW / imageAspect
    } else {
        dispH = canvasH
        dispW = canvasH * imageAspect
    }
    return ImageDisplayRect(
        left = (canvasW - dispW) / 2f,
        top  = (canvasH - dispH) / 2f,
        width = dispW,
        height = dispH,
    )
}
