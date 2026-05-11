/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 *
 * Cloned from `imageeditor-native`'s TelegramCropOverlay because Gradle inter-module
 * Compose-source sharing is fiddly (the photo module would have to expose a public
 * artefact and the video module would pull it in transitively, which couples the two
 * libraries unnecessarily). Behaviour is identical; if either side changes, mirror
 * the diff into the other.
 */
package io.element.android.libraries.videoeditor.native_.ui.components

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

@Composable
fun TelegramVideoCropOverlay(
    rectX: Float, rectY: Float, rectW: Float, rectH: Float,
    aspectLocked: Float? = null,
    onRectChange: (x: Float, y: Float, w: Float, h: Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    minSize: Float = 0.08f,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(VideoCropDragMode.None) }
    val rxState by rememberUpdatedState(rectX)
    val ryState by rememberUpdatedState(rectY)
    val rwState by rememberUpdatedState(rectW)
    val rhState by rememberUpdatedState(rectH)
    val aspectState by rememberUpdatedState(aspectLocked)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val h = canvasSize.height.coerceAtLeast(1).toFloat()
                        val rx = rxState * w; val ry = ryState * h
                        val rw = rwState * w; val rh = rhState * h
                        dragMode = pickHandleVideo(startOffset.x, startOffset.y, rx, ry, rw, rh, 36f)
                    },
                    onDragEnd = { dragMode = VideoCropDragMode.None },
                    onDragCancel = { dragMode = VideoCropDragMode.None },
                ) { change, dragDelta ->
                    change.consume()
                    val w = canvasSize.width.coerceAtLeast(1).toFloat()
                    val h = canvasSize.height.coerceAtLeast(1).toFloat()
                    val dx = dragDelta.x / w; val dy = dragDelta.y / h
                    var (cx, cy, cw, ch) = applyDragModeVideo(
                        dragMode, rxState, ryState, rwState, rhState, dx, dy, minSize)
                    aspectState?.let { ratio ->
                        if (ratio > 0f && dragMode in setOf(
                                VideoCropDragMode.NW, VideoCropDragMode.NE,
                                VideoCropDragMode.SW, VideoCropDragMode.SE, VideoCropDragMode.Pan)) {
                            val curRatio = (cw * w) / (ch * h)
                            if (curRatio > ratio) ch = (cw * w) / (ratio * h)
                            else                  cw = (ch * h * ratio) / w
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
            val rx = rectX * size.width; val ry = rectY * size.height
            val rw = rectW * size.width; val rh = rectH * size.height
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(0f, 0f), Size(size.width, ry))
            drawRect(dim, Offset(0f, ry + rh), Size(size.width, size.height - ry - rh))
            drawRect(dim, Offset(0f, ry), Size(rx, rh))
            drawRect(dim, Offset(rx + rw, ry), Size(size.width - rx - rw, rh))
            for (i in 1..2) {
                val gx = rx + rw * i / 3f; val gy = ry + rh * i / 3f
                drawLine(accentColor.copy(alpha = 0.4f),
                    Offset(gx, ry), Offset(gx, ry + rh), 1.dp.toPx())
                drawLine(accentColor.copy(alpha = 0.4f),
                    Offset(rx, gy), Offset(rx + rw, gy), 1.dp.toPx())
            }
            val border = 2.dp.toPx()
            drawLine(accentColor, Offset(rx, ry),      Offset(rx + rw, ry),      border, StrokeCap.Square)
            drawLine(accentColor, Offset(rx, ry + rh), Offset(rx + rw, ry + rh), border, StrokeCap.Square)
            drawLine(accentColor, Offset(rx, ry),      Offset(rx, ry + rh),      border, StrokeCap.Square)
            drawLine(accentColor, Offset(rx + rw, ry), Offset(rx + rw, ry + rh), border, StrokeCap.Square)
            val cornerLen = 18.dp.toPx(); val cornerThick = 4.dp.toPx()
            fun drawCorner(cx: Float, cy: Float, hx: Int, hy: Int) {
                drawLine(accentColor, Offset(cx, cy),
                    Offset(cx + hx * cornerLen, cy), cornerThick, StrokeCap.Square)
                drawLine(accentColor, Offset(cx, cy),
                    Offset(cx, cy + hy * cornerLen), cornerThick, StrokeCap.Square)
            }
            drawCorner(rx, ry, +1, +1)
            drawCorner(rx + rw, ry, -1, +1)
            drawCorner(rx, ry + rh, +1, -1)
            drawCorner(rx + rw, ry + rh, -1, -1)
        }
    }
}

private enum class VideoCropDragMode { None, NW, N, NE, E, SE, S, SW, W, Pan }

private fun pickHandleVideo(tx: Float, ty: Float,
                            rx: Float, ry: Float, rw: Float, rh: Float,
                            tol: Float): VideoCropDragMode {
    val left = abs(tx - rx) < tol; val right = abs(tx - (rx + rw)) < tol
    val top = abs(ty - ry) < tol; val bottom = abs(ty - (ry + rh)) < tol
    val insideX = tx in (rx - tol)..(rx + rw + tol)
    val insideY = ty in (ry - tol)..(ry + rh + tol)
    return when {
        left && top -> VideoCropDragMode.NW
        right && top -> VideoCropDragMode.NE
        left && bottom -> VideoCropDragMode.SW
        right && bottom -> VideoCropDragMode.SE
        left && insideY -> VideoCropDragMode.W
        right && insideY -> VideoCropDragMode.E
        top && insideX -> VideoCropDragMode.N
        bottom && insideX -> VideoCropDragMode.S
        tx in rx..(rx + rw) && ty in ry..(ry + rh) -> VideoCropDragMode.Pan
        else -> VideoCropDragMode.None
    }
}

private data class VideoRect(val x: Float, val y: Float, val w: Float, val h: Float)

private fun applyDragModeVideo(mode: VideoCropDragMode,
                                x: Float, y: Float, w: Float, h: Float,
                                dx: Float, dy: Float, minSize: Float): VideoRect {
    var nx = x; var ny = y; var nw = w; var nh = h
    when (mode) {
        VideoCropDragMode.NW -> { nx = (x + dx).coerceIn(0f, x + w - minSize); ny = (y + dy).coerceIn(0f, y + h - minSize); nw = w - (nx - x); nh = h - (ny - y) }
        VideoCropDragMode.NE -> { ny = (y + dy).coerceIn(0f, y + h - minSize); nw = (w + dx).coerceIn(minSize, 1f - x); nh = h - (ny - y) }
        VideoCropDragMode.SW -> { nx = (x + dx).coerceIn(0f, x + w - minSize); nw = w - (nx - x); nh = (h + dy).coerceIn(minSize, 1f - y) }
        VideoCropDragMode.SE -> { nw = (w + dx).coerceIn(minSize, 1f - x); nh = (h + dy).coerceIn(minSize, 1f - y) }
        VideoCropDragMode.N  -> { ny = (y + dy).coerceIn(0f, y + h - minSize); nh = h - (ny - y) }
        VideoCropDragMode.S  -> { nh = (h + dy).coerceIn(minSize, 1f - y) }
        VideoCropDragMode.W  -> { nx = (x + dx).coerceIn(0f, x + w - minSize); nw = w - (nx - x) }
        VideoCropDragMode.E  -> { nw = (w + dx).coerceIn(minSize, 1f - x) }
        VideoCropDragMode.Pan -> {
            nx = (x + dx).coerceIn(0f, max(0f, 1f - w))
            ny = (y + dy).coerceIn(0f, max(0f, 1f - h))
        }
        VideoCropDragMode.None -> Unit
    }
    return VideoRect(nx, ny, min(nw, 1f - nx), min(nh, 1f - ny))
}
