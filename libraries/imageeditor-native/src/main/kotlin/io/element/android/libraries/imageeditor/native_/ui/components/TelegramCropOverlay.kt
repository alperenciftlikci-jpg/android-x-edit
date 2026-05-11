/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Telegram-style crop rectangle overlay. Drawn on top of the photo preview when the user
 * selects the Crop tab.
 *
 *   ┌──────────────┐  ← outer dim
 *   │   ░░░░░░░░░░ │
 *   │  ┌────┬────┐ │  ← active rect with corner + edge handles + 3x3 grid
 *   │  │░░░░│░░░░│ │
 *   │  ├────┼────┤ │
 *   │  │░░░░│░░░░│ │
 *   │  └────┴────┘ │
 *   │   ░░░░░░░░░░ │
 *   └──────────────┘
 *
 * Coords are normalised image-space (0..1). `aspectLocked` (when non-null) constrains the
 * rectangle to that ratio; corner handles preserve the ratio by adjusting the opposite axis.
 */
@Composable
fun TelegramCropOverlay(
    rectX: Float, rectY: Float, rectW: Float, rectH: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    aspectLocked: Float? = null,
    onRectChange: (x: Float, y: Float, w: Float, h: Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    minSize: Float = 0.08f,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(CropDragMode.None) }

    // Always-fresh state for the gesture coroutine.
    val rxState by rememberUpdatedState(rectX)
    val ryState by rememberUpdatedState(rectY)
    val rwState by rememberUpdatedState(rectW)
    val rhState by rememberUpdatedState(rectH)
    val aspectState by rememberUpdatedState(aspectLocked)
    val srcWState by rememberUpdatedState(sourceWidth)
    val srcHState by rememberUpdatedState(sourceHeight)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val img = computeDisplayedImageRect(
                            canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                            srcWState, srcHState,
                        )
                        // Hit-test in canvas pixels but against the rect projected into the
                        // image's displayed area — that's what the user sees and touches.
                        val rx = img.left + rxState * img.width
                        val ry = img.top + ryState * img.height
                        val rw = rwState * img.width
                        val rh = rhState * img.height
                        // Corners are hard to hit precisely on phones; bias toward selecting
                        // a corner over an edge by using a tolerance large enough that "near"
                        // taps still register diagonally. Previously 48 px was too tight on
                        // dense displays — users grabbed an edge handle instead of the corner
                        // and got single-axis drag.
                        val tol = 72f
                        dragMode = pickHandle(startOffset.x, startOffset.y, rx, ry, rw, rh, tol)
                    },
                    onDragEnd = { dragMode = CropDragMode.None },
                    onDragCancel = { dragMode = CropDragMode.None },
                ) { change, dragDelta ->
                    change.consume()
                    val img = computeDisplayedImageRect(
                        canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                        srcWState, srcHState,
                    )
                    if (img.width <= 0f || img.height <= 0f) return@detectDragGestures
                    // Convert pixel drag into image-fraction. Previously we divided by canvas
                    // size, which made the rect coords relative to the canvas (including
                    // letterbox) — but the rect needs to live in image-space.
                    val dx = dragDelta.x / img.width
                    val dy = dragDelta.y / img.height

                    val nx = rxState
                    val ny = ryState
                    val nw = rwState
                    val nh = rhState
                    var (cx, cy, cw, ch) = applyCropDragMode(
                        mode = dragMode,
                        x = nx, y = ny, w = nw, h = nh,
                        dx = dx, dy = dy,
                        minSize = minSize,
                    )

                    // Aspect lock. Ratio is expressed in source-image pixels (e.g. 1:1 means
                    // a square in source pixels, NOT a square in normalised coords).
                    aspectState?.let { ratio ->
                        if (ratio > 0f && dragMode in setOf(
                                CropDragMode.NW, CropDragMode.NE, CropDragMode.SW, CropDragMode.SE,
                                CropDragMode.Pan)) {
                            val srcW = srcWState.coerceAtLeast(1).toFloat()
                            val srcH = srcHState.coerceAtLeast(1).toFloat()
                            val curRatio = (cw * srcW) / (ch * srcH)
                            if (curRatio > ratio) {
                                // Too wide → shrink width (in normalised coords)
                                cw = (ch * srcH * ratio) / srcW
                            } else {
                                ch = (cw * srcW) / (ratio * srcH)
                            }
                            cx = cx.coerceIn(0f, 1f - cw)
                            cy = cy.coerceIn(0f, 1f - ch)
                        }
                    }

                    onRectChange(cx, cy, cw, ch)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())

            val img = computeDisplayedImageRect(
                size.width, size.height, sourceWidth, sourceHeight)
            val rx = img.left + rectX * img.width
            val ry = img.top + rectY * img.height
            val rw = rectW * img.width
            val rh = rectH * img.height

            // Dim only the part of the IMAGE that's outside the crop (not the letterbox —
            // that's empty canvas). Telegram darkens the to-be-cropped area inside the photo.
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(img.left, img.top), Size(img.width, ry - img.top))
            drawRect(dim, Offset(img.left, ry + rh),
                Size(img.width, img.top + img.height - ry - rh))
            drawRect(dim, Offset(img.left, ry), Size(rx - img.left, rh))
            drawRect(dim, Offset(rx + rw, ry),
                Size(img.left + img.width - rx - rw, rh))

            // Rule-of-thirds grid.
            for (i in 1..2) {
                val gx = rx + rw * i / 3f
                val gy = ry + rh * i / 3f
                drawLine(
                    color = accentColor.copy(alpha = 0.4f),
                    start = Offset(gx, ry), end = Offset(gx, ry + rh),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = accentColor.copy(alpha = 0.4f),
                    start = Offset(rx, gy), end = Offset(rx + rw, gy),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Border.
            val border = 2.dp.toPx()
            drawLine(accentColor, Offset(rx, ry),           Offset(rx + rw, ry),           border, StrokeCap.Square)
            drawLine(accentColor, Offset(rx, ry + rh),      Offset(rx + rw, ry + rh),      border, StrokeCap.Square)
            drawLine(accentColor, Offset(rx, ry),           Offset(rx, ry + rh),           border, StrokeCap.Square)
            drawLine(accentColor, Offset(rx + rw, ry),      Offset(rx + rw, ry + rh),      border, StrokeCap.Square)

            // Corner accents — Telegram uses chunky L-shaped corners.
            val cornerLen = 18.dp.toPx()
            val cornerThick = 4.dp.toPx()
            fun drawCorner(cx: Float, cy: Float, hx: Int, hy: Int) {
                drawLine(accentColor,
                    Offset(cx, cy),
                    Offset(cx + hx * cornerLen, cy),
                    cornerThick, StrokeCap.Square)
                drawLine(accentColor,
                    Offset(cx, cy),
                    Offset(cx, cy + hy * cornerLen),
                    cornerThick, StrokeCap.Square)
            }
            drawCorner(rx, ry, +1, +1)
            drawCorner(rx + rw, ry, -1, +1)
            drawCorner(rx, ry + rh, +1, -1)
            drawCorner(rx + rw, ry + rh, -1, -1)
        }
    }
}

private data class DisplayedImageRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Compute the rect inside the canvas where the photo is actually drawn under
 *  `ContentScale.Fit`. Needed because the crop overlay sits over the full canvas (including
 *  letterbox margins) but the rect coordinates must be relative to the photo, not the
 *  canvas. */
private fun computeDisplayedImageRect(
    canvasW: Float, canvasH: Float, srcW: Int, srcH: Int,
): DisplayedImageRect {
    if (canvasW <= 0f || canvasH <= 0f || srcW <= 0 || srcH <= 0) {
        return DisplayedImageRect(0f, 0f, canvasW, canvasH)
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
    return DisplayedImageRect(
        left = (canvasW - dispW) / 2f,
        top  = (canvasH - dispH) / 2f,
        width = dispW,
        height = dispH,
    )
}

private enum class CropDragMode { None, NW, N, NE, E, SE, S, SW, W, Pan }

private fun pickHandle(tx: Float, ty: Float,
                       rx: Float, ry: Float, rw: Float, rh: Float, tol: Float): CropDragMode {
    val left = abs(tx - rx) < tol
    val right = abs(tx - (rx + rw)) < tol
    val top = abs(ty - ry) < tol
    val bottom = abs(ty - (ry + rh)) < tol
    val insideX = tx in (rx - tol)..(rx + rw + tol)
    val insideY = ty in (ry - tol)..(ry + rh + tol)

    return when {
        left && top      -> CropDragMode.NW
        right && top     -> CropDragMode.NE
        left && bottom   -> CropDragMode.SW
        right && bottom  -> CropDragMode.SE
        left   && insideY -> CropDragMode.W
        right  && insideY -> CropDragMode.E
        top    && insideX -> CropDragMode.N
        bottom && insideX -> CropDragMode.S
        tx in rx..(rx + rw) && ty in ry..(ry + rh) -> CropDragMode.Pan
        else -> CropDragMode.None
    }
}

private data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

private fun applyCropDragMode(mode: CropDragMode,
                          x: Float, y: Float, w: Float, h: Float,
                          dx: Float, dy: Float, minSize: Float): Rect {
    var nx = x; var ny = y; var nw = w; var nh = h
    when (mode) {
        CropDragMode.NW -> { nx = (x + dx).coerceIn(0f, x + w - minSize); ny = (y + dy).coerceIn(0f, y + h - minSize); nw = w - (nx - x); nh = h - (ny - y) }
        CropDragMode.NE -> { ny = (y + dy).coerceIn(0f, y + h - minSize); nw = (w + dx).coerceIn(minSize, 1f - x); nh = h - (ny - y) }
        CropDragMode.SW -> { nx = (x + dx).coerceIn(0f, x + w - minSize); nw = w - (nx - x); nh = (h + dy).coerceIn(minSize, 1f - y) }
        CropDragMode.SE -> { nw = (w + dx).coerceIn(minSize, 1f - x); nh = (h + dy).coerceIn(minSize, 1f - y) }
        CropDragMode.N  -> { ny = (y + dy).coerceIn(0f, y + h - minSize); nh = h - (ny - y) }
        CropDragMode.S  -> { nh = (h + dy).coerceIn(minSize, 1f - y) }
        CropDragMode.W  -> { nx = (x + dx).coerceIn(0f, x + w - minSize); nw = w - (nx - x) }
        CropDragMode.E  -> { nw = (w + dx).coerceIn(minSize, 1f - x) }
        CropDragMode.Pan -> {
            nx = (x + dx).coerceIn(0f, max(0f, 1f - w))
            ny = (y + dy).coerceIn(0f, max(0f, 1f - h))
        }
        CropDragMode.None -> Unit
    }
    return Rect(nx, ny, min(nw, 1f - nx), min(nh, 1f - ny))
}
