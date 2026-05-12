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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.element.android.libraries.imageeditor.native_.CropParams

/**
 * Transparent touch-capture overlay for the paint pipeline. Forwards every touch sample to
 * the native paint engine in SOURCE-pixel coordinates that respect the current crop /
 * rotation / mirror state. Native bakes the stroke at the source pixel, then crop pass
 * transforms it back to display position — net effect: the stroke lands exactly under the
 * user's finger regardless of how the photo is currently rotated, mirrored, or cropped.
 *
 * No Compose-side fake preview is drawn here — the screen's preview snapshot flow re-renders
 * at ~60 fps during a stroke so the user sees the real native paint FBO updating live.
 */
@Composable
fun TelegramDrawingCanvas(
    enabled: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    cropParams: CropParams,
    onStrokeBegin: (xSrc: Float, ySrc: Float, pressure: Float) -> Unit,
    onStrokeExtend: (xSrc: Float, ySrc: Float, pressure: Float) -> Unit,
    onStrokeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Keep the matrix fresh inside the long-lived pointer-input coroutine. Re-keying
    // pointerInput on cropParams every recomposition would cancel an in-progress stroke
    // every time anything else in CropParams changed.
    val cropParamsState = rememberUpdatedState(cropParams)
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(enabled, sourceWidth, sourceHeight) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (sxDown, syDown) = canvasToSource(
                        down.position.x, down.position.y,
                        canvasSize, sourceWidth, sourceHeight,
                        cropParamsState.value,
                    )
                    onStrokeBegin(sxDown, syDown, 1f)
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
                            canvasSize, sourceWidth, sourceHeight,
                            cropParamsState.value,
                        )
                        onStrokeExtend(sx, sy, 1f)
                        change.consume()
                    }
                }
            },
    )
}

/**
 * Touch position (canvas pixels) → source pixel that, when transformed by the current
 * crop matrix, ends up under the user's finger.
 *
 *   1. Compute the output bitmap dims (cropped + rotated) — that's what the user sees.
 *   2. Letterbox math turns the touch into an output uv in `[0, 1]`.
 *   3. Apply `CropUvMatrix` (same one the GL shader uses) to turn output uv → source uv.
 *   4. Multiply source uv by `srcW / srcH` to get the source pixel.
 *
 * The paint engine then writes to that source pixel in the un-cropped FBO; the next render
 * pass applies the crop transform to display it under the user's finger.
 */
private fun canvasToSource(
    px: Float, py: Float,
    canvasSize: IntSize, srcW: Int, srcH: Int,
    cropParams: CropParams,
): Pair<Float, Float> {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || srcW <= 0 || srcH <= 0) {
        return 0f to 0f
    }
    val (outW, outH) = CropUvMatrix.outputDims(srcW, srcH, cropParams)
    val canvasW = canvasSize.width.toFloat()
    val canvasH = canvasSize.height.toFloat()
    val imageAspect = outW.toFloat() / outH.toFloat()
    val canvasAspect = canvasW / canvasH
    val displayedW: Float; val displayedH: Float
    if (imageAspect > canvasAspect) {
        displayedW = canvasW
        displayedH = canvasW / imageAspect
    } else {
        displayedH = canvasH
        displayedW = canvasH * imageAspect
    }
    val offsetX = (canvasW - displayedW) / 2f
    val offsetY = (canvasH - displayedH) / 2f
    val ox = ((px - offsetX) / displayedW).coerceIn(0f, 1f)
    val oy = ((py - offsetY) / displayedH).coerceIn(0f, 1f)
    val m = CropUvMatrix.build(cropParams)
    val (srcUvX, srcUvY) = CropUvMatrix.apply(m, ox, oy)
    return (srcUvX * srcW).coerceIn(0f, srcW.toFloat()) to
            (srcUvY * srcH).coerceIn(0f, srcH.toFloat())
}
