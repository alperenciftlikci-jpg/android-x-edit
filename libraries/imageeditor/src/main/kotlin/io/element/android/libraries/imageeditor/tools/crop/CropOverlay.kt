/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.libraries.core.perf.TracedGesture
import kotlin.math.abs

private enum class HandleEdge { TopLeft, TopRight, BottomLeft, BottomRight, Inside, None }

/**
 * Interactive crop overlay. Displays the current [rect] over the image area along
 * with corner handles. Dragging a corner resizes the rectangle. When [aspectRatio]
 * is non-null, the opposite corner is held fixed and the dragged corner is
 * constrained to maintain the ratio.
 *
 * The rect is reported back via [onRectChange] in normalized image-space (0f..1f).
 */
@Composable
fun CropOverlay(
    rect: CropRect,
    aspectRatio: Float?,
    onRectChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleSizePx = with(density) { 22.dp.toPx() }
    val handleHitSlop = with(density) { 32.dp.toPx() }
    val borderWidthPx = with(density) { 2.dp.toPx() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingHandle by remember { mutableStateOf(HandleEdge.None) }
    val gesture = remember { TracedGesture("imageeditor.crop.gesture") }

    // Keep refs that always read the latest rect/callback from state. Without these
    // the gesture coroutine captures the rect at first composition and every drag
    // delta is applied to the same stale rect — producing the jittery feedback.
    val currentRect by rememberUpdatedState(rect)
    val onChange by rememberUpdatedState(onRectChange)
    // Local "live" rect used during a drag. While set, it overrides the parameter `rect` for
    // rendering. We commit to the outer state via `onChange` only at drag-end. Without this, every
    // drag delta wrote to `state.cropRect` in the parent (`ImageStage`), which reads `cropRect`
    // for the overlay — that turned every 1-pixel finger movement into a full ImageStage
    // recomposition and held the gesture at ~47 fps.
    var liveRect by remember { mutableStateOf<CropRect?>(null) }

    Canvas(
        modifier = modifier
            .pointerInput(aspectRatio) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        draggingHandle = pickHandle(currentRect, canvasSize, startOffset, handleHitSlop)
                        if (draggingHandle != HandleEdge.None) {
                            liveRect = currentRect
                            gesture.start()
                        }
                    },
                    onDragEnd = {
                        if (draggingHandle != HandleEdge.None) {
                            liveRect?.let { onChange(it) }
                            liveRect = null
                            gesture.finish()
                        }
                        draggingHandle = HandleEdge.None
                    },
                    onDragCancel = {
                        if (draggingHandle != HandleEdge.None) {
                            liveRect = null
                            gesture.cancel()
                        }
                        draggingHandle = HandleEdge.None
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (draggingHandle == HandleEdge.None) return@detectDragGestures
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val h = canvasSize.height.coerceAtLeast(1).toFloat()
                        val deltaXNorm = drag.x / w
                        val deltaYNorm = drag.y / h
                        val updated = applyHandleDrag(
                            rect = liveRect ?: currentRect,
                            handle = draggingHandle,
                            dx = deltaXNorm,
                            dy = deltaYNorm,
                            aspectRatio = aspectRatio,
                            canvasW = w,
                            canvasH = h,
                        )
                        liveRect = updated
                    },
                )
            },
    ) {
        canvasSize = IntSize(size.width.toInt(), size.height.toInt())

        val effectiveRect = liveRect ?: rect
        val cropLeftPx = effectiveRect.left * size.width
        val cropTopPx = effectiveRect.top * size.height
        val cropRightPx = effectiveRect.right * size.width
        val cropBottomPx = effectiveRect.bottom * size.height

        // Dim the area outside the crop rect via GPU clip + drawRect, instead of CPU Path.op
        // Difference. The old approach allocated three Path objects per frame and ran Skia's
        // Path.op() — a CPU-bound geometric subtraction. Hardware-accelerated `clipRect` with
        // ClipOp.Difference inverts the clip onto the GPU stencil buffer; the subsequent
        // `drawRect` then only paints the pixels OUTSIDE the crop rect. Zero per-frame
        // allocation, GPU-side compositing, ~ms saved per frame on a busy scene.
        clipRect(
            left = cropLeftPx,
            top = cropTopPx,
            right = cropRightPx,
            bottom = cropBottomPx,
            clipOp = ClipOp.Difference,
        ) {
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
            )
        }

        // Crop rectangle border.
        drawRect(
            color = Color.White,
            topLeft = Offset(cropLeftPx, cropTopPx),
            size = Size(cropRightPx - cropLeftPx, cropBottomPx - cropTopPx),
            style = Stroke(width = borderWidthPx),
        )

        // Rule-of-thirds gridlines.
        val third1X = cropLeftPx + (cropRightPx - cropLeftPx) / 3f
        val third2X = cropLeftPx + (cropRightPx - cropLeftPx) * 2f / 3f
        val third1Y = cropTopPx + (cropBottomPx - cropTopPx) / 3f
        val third2Y = cropTopPx + (cropBottomPx - cropTopPx) * 2f / 3f
        val gridStroke = Stroke(width = borderWidthPx / 2f)
        val gridColor = Color.White.copy(alpha = 0.5f)
        drawLine(gridColor, Offset(third1X, cropTopPx), Offset(third1X, cropBottomPx), gridStroke.width)
        drawLine(gridColor, Offset(third2X, cropTopPx), Offset(third2X, cropBottomPx), gridStroke.width)
        drawLine(gridColor, Offset(cropLeftPx, third1Y), Offset(cropRightPx, third1Y), gridStroke.width)
        drawLine(gridColor, Offset(cropLeftPx, third2Y), Offset(cropRightPx, third2Y), gridStroke.width)

        // Corner handles.
        val handles = listOf(
            Offset(cropLeftPx, cropTopPx),
            Offset(cropRightPx, cropTopPx),
            Offset(cropLeftPx, cropBottomPx),
            Offset(cropRightPx, cropBottomPx),
        )
        for (h in handles) {
            drawRect(
                color = Color.White,
                topLeft = Offset(h.x - handleSizePx / 2f, h.y - handleSizePx / 2f),
                size = Size(handleSizePx, handleSizePx),
            )
        }
    }
}

private fun pickHandle(
    rect: CropRect,
    canvasSize: IntSize,
    pos: Offset,
    hitSlopPx: Float,
): HandleEdge {
    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
    val tl = Offset(rect.left * w, rect.top * h)
    val tr = Offset(rect.right * w, rect.top * h)
    val bl = Offset(rect.left * w, rect.bottom * h)
    val br = Offset(rect.right * w, rect.bottom * h)

    fun within(target: Offset): Boolean =
        abs(pos.x - target.x) < hitSlopPx && abs(pos.y - target.y) < hitSlopPx

    val insideRect = pos.x in tl.x..tr.x && pos.y in tl.y..bl.y

    return when {
        within(tl) -> HandleEdge.TopLeft
        within(tr) -> HandleEdge.TopRight
        within(bl) -> HandleEdge.BottomLeft
        within(br) -> HandleEdge.BottomRight
        insideRect -> HandleEdge.Inside
        else -> HandleEdge.None
    }
}

/** Minimum crop side, normalized — keeps the rect from collapsing to nothing. */
private const val MIN_CROP_NORM = 0.08f

private fun applyHandleDrag(
    rect: CropRect,
    handle: HandleEdge,
    dx: Float,
    dy: Float,
    aspectRatio: Float?,
    canvasW: Float,
    canvasH: Float,
): CropRect {
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom

    when (handle) {
        HandleEdge.TopLeft -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_NORM)
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_NORM)
        }
        HandleEdge.TopRight -> {
            right = (right + dx).coerceIn(left + MIN_CROP_NORM, 1f)
            top = (top + dy).coerceIn(0f, bottom - MIN_CROP_NORM)
        }
        HandleEdge.BottomLeft -> {
            left = (left + dx).coerceIn(0f, right - MIN_CROP_NORM)
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_NORM, 1f)
        }
        HandleEdge.BottomRight -> {
            right = (right + dx).coerceIn(left + MIN_CROP_NORM, 1f)
            bottom = (bottom + dy).coerceIn(top + MIN_CROP_NORM, 1f)
        }
        HandleEdge.Inside -> {
            // Translate the whole rect, clamping so it stays inside (0,0)..(1,1).
            val rectW = right - left
            val rectH = bottom - top
            val newLeft = (left + dx).coerceIn(0f, 1f - rectW)
            val newTop = (top + dy).coerceIn(0f, 1f - rectH)
            return CropRect(newLeft, newTop, newLeft + rectW, newTop + rectH)
        }
        HandleEdge.None -> Unit
    }

    if (aspectRatio == null || aspectRatio <= 0f) {
        return CropRect(left, top, right, bottom)
    }

    // Aspect-locked: keep the corner opposite to the dragged one fixed and force
    // the new height to satisfy width * canvasW / aspectRatio == height * canvasH.
    val newWidth = right - left
    val targetHeight = (newWidth * canvasW / aspectRatio) / canvasH
    when (handle) {
        HandleEdge.TopLeft -> {
            top = (bottom - targetHeight).coerceAtLeast(0f).coerceAtMost(bottom - MIN_CROP_NORM)
        }
        HandleEdge.TopRight -> {
            top = (bottom - targetHeight).coerceAtLeast(0f).coerceAtMost(bottom - MIN_CROP_NORM)
        }
        HandleEdge.BottomLeft -> {
            bottom = (top + targetHeight).coerceAtMost(1f).coerceAtLeast(top + MIN_CROP_NORM)
        }
        HandleEdge.BottomRight -> {
            bottom = (top + targetHeight).coerceAtMost(1f).coerceAtLeast(top + MIN_CROP_NORM)
        }
        // Inside is handled before this block via early return.
        HandleEdge.Inside, HandleEdge.None -> Unit
    }
    return CropRect(left, top, right, bottom)
}
