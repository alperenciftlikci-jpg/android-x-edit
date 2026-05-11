/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/**
 * Transparent overlay that captures touch input and forwards each `(x, y, pressure)` sample
 * to the native paint engine. Coordinates are translated from canvas-local pixels into the
 * source bitmap's pixel space before they're handed off — so a stamp at `(100, 100)` in
 * source resolution always lands at that bitmap address regardless of how the preview is
 * scaled on screen.
 *
 * The native PaintEngine handles densification + stamp rendering; we just stream raw
 * pointer samples and call `endStroke` on lift.
 */
@Composable
fun TelegramDrawingCanvas(
    enabled: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    onStrokeBegin: (xSrc: Float, ySrc: Float, pressure: Float) -> Unit,
    onStrokeExtend: (xSrc: Float, ySrc: Float, pressure: Float) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled, sourceWidth, sourceHeight) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (sxDown, syDown) = canvasToSource(
                        down.position.x, down.position.y, canvasSize, sourceWidth, sourceHeight)
                    onStrokeBegin(sxDown, syDown, down.pressure.coerceIn(0.05f, 1f))
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            onStrokeEnd()
                            break
                        }
                        val (sx, sy) = canvasToSource(
                            change.position.x, change.position.y,
                            canvasSize, sourceWidth, sourceHeight)
                        onStrokeExtend(sx, sy, change.pressure.coerceIn(0.05f, 1f))
                        change.consume()
                    }
                }
            }
            // We use onSizeChanged inside a custom Modifier rather than a separate one so the
            // pointerInput sees the correct size on first dispatch (before recomposition).
            .let { mod ->
                mod.then(Modifier.fillMaxSize()).then(
                    Modifier.pointerInput(Unit) {
                        // No-op extra pointerInput — placeholder to chain `onSizeChanged`.
                    }
                )
            },
    ) {
        // Capture the canvas size for coordinate translation.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
        }
    }
}

private fun canvasToSource(
    px: Float, py: Float,
    canvasSize: IntSize, srcW: Int, srcH: Int,
): Pair<Float, Float> {
    // The preview Image uses ContentScale.Fit, which letterboxes the photo to the largest
    // rectangle that fits inside the canvas while preserving aspect ratio. The drawing canvas
    // sits ON the full canvas (including letterbox), so a touch at canvas (0, 0) does NOT
    // correspond to source (0, 0) when the photo is letterboxed top/bottom or left/right.
    //
    // We need to compute the actual displayed-image rect within the canvas and translate
    // touches into that rect. Without this fix, paint strokes drift away from the user's
    // finger by the amount of the letterbox margin — exactly the bug the user reported.
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || srcW <= 0 || srcH <= 0) {
        return 0f to 0f
    }
    val canvasW = canvasSize.width.toFloat()
    val canvasH = canvasSize.height.toFloat()
    val imageAspect = srcW.toFloat() / srcH.toFloat()
    val canvasAspect = canvasW / canvasH
    val displayedW: Float
    val displayedH: Float
    if (imageAspect > canvasAspect) {
        // Photo is wider than canvas → fits to width, letterbox top/bottom.
        displayedW = canvasW
        displayedH = canvasW / imageAspect
    } else {
        // Photo is taller than canvas → fits to height, pillarbox left/right.
        displayedH = canvasH
        displayedW = canvasH * imageAspect
    }
    val offsetX = (canvasW - displayedW) / 2f
    val offsetY = (canvasH - displayedH) / 2f
    // Touches outside the displayed photo are clamped to the edge so a stroke that runs off
    // the photo's right edge stops at the right edge instead of jumping to the photo's
    // opposite side via wrap. Clamping is in fraction-space.
    val fx = ((px - offsetX) / displayedW).coerceIn(0f, 1f)
    val fy = ((py - offsetY) / displayedH).coerceIn(0f, 1f)
    return fx * srcW to fy * srcH
}
