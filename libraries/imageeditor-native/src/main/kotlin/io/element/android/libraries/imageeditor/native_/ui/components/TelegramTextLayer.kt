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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.libraries.imageeditor.native_.ui.state.TextFrameType
import io.element.android.libraries.imageeditor.native_.ui.state.TextItemEdit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Faithful Compose port of Telegram's `TextPaintView` entity layer (see
 * `org.telegram.ui.Components.Paint.Views.LPhotoPaintView` / `TextPaintView`).
 *
 *   * Each text item is a movable / scalable / rotatable Box, positioned at its
 *     normalised image-space coordinates over the photo preview.
 *   * Exactly one item is selected at a time. The selected item shows a dashed
 *     border (Telegram calls this the `SelectionView`).
 *   * The selected entity in editing mode hosts a focused `BasicTextField`,
 *     soft keyboard pops up automatically (same as Telegram's `beginEditing`).
 *   * Single-finger drag pans, two-finger gesture scales + rotates in one go.
 *   * Tap on selected = re-edit. Tap on different = swap. Tap on background =
 *     deselect and commit (mirrors LPhotoPaintView.selectEntity(null)).
 *
 * Frame types match Telegram's `currentType` field in TextPaintView:
 *   Solid / Semi / Outline / Plain — colour decisions follow the same brightness
 *   rules `TextPaintView.updateColor()` uses.
 *
 * Live rendering uses the Compose layer only — native pipeline does NOT composite
 * text during preview, so there is no double-render and no native round-trip per
 * keystroke. Native compositing happens once at export time, identical to how
 * Telegram bakes entities into the final JPEG.
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
    /** Source image dimensions — required so font size and position can be expressed in
     *  source-pixel space (the same space used at export). Otherwise the on-screen text
     *  size and the JPEG'd text size disagree by the image-to-screen scale factor. */
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF50A8EB),
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Letterbox-aware projection of the source bitmap onto our canvas. Matches Telegram's
    // entitiesView at `paintingSize` × baseScale layout — every entity is rendered as if
    // it lived in source-pixel space, scaled uniformly onto the visible photo rect.
    val imageRect = remember(canvasSize, sourceWidth, sourceHeight) {
        computeImageDisplayRect(
            canvasSize.width.toFloat(), canvasSize.height.toFloat(),
            sourceWidth, sourceHeight,
        )
    }
    val sourceToScreen = if (sourceWidth > 0) imageRect.width / sourceWidth else 1f

    Box(
        modifier = modifier
            .fillMaxSize()
            // Background-tap deselect — tapping outside any text entity commits the
            // current edit. Telegram does the same when the user taps the photo.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Final)
                        val change = ev.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            if (!moved && !change.isConsumed) onSelect(null)
                            break
                        }
                        if (!change.isConsumed &&
                            (abs(change.position.x - first.position.x) > 8 ||
                             abs(change.position.y - first.position.y) > 8)) {
                            moved = true
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
                TextEntity(
                    item = item,
                    isSelected = item.id == selectedId,
                    isEditing = isEditing && item.id == selectedId,
                    imageRect = imageRect,
                    sourceToScreen = sourceToScreen,
                    accentColor = accentColor,
                    onTap = {
                        if (item.id == selectedId) onBeginEditing(item.id)
                        else onSelect(item.id)
                    },
                    onMove = { dx, dy ->
                        // dx/dy are in canvas (screen) pixels; convert to image-fraction.
                        if (imageRect.width > 0f && imageRect.height > 0f) {
                            onItemUpdate(item.id) { current ->
                                current.copy(
                                    centerX = (current.centerX + dx / imageRect.width)
                                        .coerceIn(0f, 1f),
                                    centerY = (current.centerY + dy / imageRect.height)
                                        .coerceIn(0f, 1f),
                                )
                            }
                        }
                    },
                    onScale = { factor ->
                        onItemUpdate(item.id) { current ->
                            current.copy(scale = (current.scale * factor).coerceIn(0.3f, 6f))
                        }
                    },
                    onRotate = { deltaRad ->
                        onItemUpdate(item.id) { current ->
                            current.copy(rotationRad = current.rotationRad + deltaRad)
                        }
                    },
                    onTextChange = { newText -> onTextChange(item.id, newText) },
                    onEndIme = onEndEditing,
                )
            }
        }
    }
}

@Composable
private fun TextEntity(
    item: TextItemEdit,
    isSelected: Boolean,
    isEditing: Boolean,
    imageRect: ImageDisplayRect,
    /** Multiplier turning source-pixel sizes into on-screen pixel sizes. fontSize and any
     *  other "in image pixels" measurement gets multiplied by this for display. */
    sourceToScreen: Float,
    accentColor: Color,
    onTap: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onScale: (Float) -> Unit,
    onRotate: (Float) -> Unit,
    onTextChange: (String) -> Unit,
    onEndIme: () -> Unit,
) {
    if (imageRect.width <= 0f || imageRect.height <= 0f) return

    var selfSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                // Telegram-style centred positioning: place the entity's LAYOUT centre on the
                // photo's (centerX, centerY). graphicsLayer's scale + rotation pivots around
                // the layout centre (TransformOrigin.Center), so visual centre tracks the
                // image-space coords regardless of scale or rotation. Position is relative
                // to imageRect (letterbox-aware), so (0.5, 0.5) always lands on photo centre.
                .align(Alignment.TopStart)
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        x = (imageRect.left + item.centerX * imageRect.width -
                                selfSize.width / 2f).toInt(),
                        y = (imageRect.top + item.centerY * imageRect.height -
                                selfSize.height / 2f).toInt(),
                    )
                }
                .onSizeChanged { selfSize = it }
                .graphicsLayer {
                    // Scale + rotation only — anchored at the entity's centre.
                    transformOrigin = TransformOrigin.Center
                    scaleX = item.scale
                    scaleY = item.scale
                    rotationZ = Math.toDegrees(item.rotationRad.toDouble()).toFloat()
                }
                .pointerInput(item.id) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        first.consume()
                        var lastSingleX = first.position.x
                        var lastSingleY = first.position.y
                        var prevDist: Float? = null
                        var prevAngle: Float? = null
                        var moved = false

                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)
                            val active = ev.changes.filter { it.pressed }
                            if (active.isEmpty()) {
                                if (!moved) onTap()
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
                                    onScale(dist / pd)
                                }
                                if (pa != null) {
                                    var da = angle - pa
                                    // Wrap normalisation so a discontinuity doesn't spin
                                    // the text by ~2π in a single frame.
                                    val twoPi = (2.0 * Math.PI).toFloat()
                                    if (da > Math.PI.toFloat()) da -= twoPi
                                    if (da < -Math.PI.toFloat()) da += twoPi
                                    onRotate(da)
                                }
                                prevDist = dist
                                prevAngle = angle
                                moved = true
                            } else {
                                val p = active[0].position
                                val dx = p.x - lastSingleX
                                val dy = p.y - lastSingleY
                                if (abs(dx) + abs(dy) > 0.5f) {
                                    onMove(dx, dy)
                                    lastSingleX = p.x
                                    lastSingleY = p.y
                                    moved = true
                                }
                                prevDist = null
                                prevAngle = null
                            }
                        }
                    }
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
    // fontSizePx is in source-pixel space — that's what the native exporter (TextRenderer)
    // uses when baking text into the JPEG. To draw the editor preview at the EXACT same
    // proportional size, we multiply by the source-to-screen scale ratio. Without this the
    // edit screen showed text at "source pixel size = on-screen pixel size", which on most
    // photos was much bigger than what the export ended up with (smaller on screen than in
    // the bitmap when source > canvas, or vice versa).
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
            // 50 ms tail mirrors Telegram's 300 ms keyboard-reveal delay but tightens
            // it for Compose — the layout pass completes well before that.
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
