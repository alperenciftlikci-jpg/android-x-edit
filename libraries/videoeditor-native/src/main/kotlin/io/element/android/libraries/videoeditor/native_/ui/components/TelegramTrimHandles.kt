/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Telegram-style trim selector — two yellow handles at the start/end of the selected range,
 * with a translucent overlay over the unselected regions. Designed to be stacked on top of
 * a [TelegramTimelineThumbnails] strip via a parent Box.
 *
 * `startFraction` / `endFraction` are normalised positions in `[0, 1]` along the strip.
 * Drag a handle to move that endpoint; the parent receives only commit-on-end via
 * `onTrimChange` (called continuously during drag for live preview).
 *
 * Hit-testing: 24 dp slop around each handle; if the user grabs the middle of the selection
 * the whole range pans (start + end shift together, clamped to `[0, 1]`).
 */
@Composable
fun TelegramTrimHandles(
    startFraction: Float,
    endFraction: Float,
    onTrimChange: (start: Float, end: Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFE5BB3B),    // Telegram yellow
    minSelectionFraction: Float = 0.02f,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.None) }

    val startState by rememberUpdatedState(startFraction)
    val endState   by rememberUpdatedState(endFraction)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val touchX = startOffset.x
                        val startPx = startState * w
                        val endPx   = endState * w
                        val tolPx = 32f
                        dragMode = when {
                            abs(touchX - startPx) < tolPx -> DragMode.Start
                            abs(touchX - endPx) < tolPx   -> DragMode.End
                            touchX > startPx && touchX < endPx -> DragMode.Pan
                            else -> DragMode.None
                        }
                    },
                    onDragEnd = { dragMode = DragMode.None },
                    onDragCancel = { dragMode = DragMode.None },
                ) { change, dragDelta ->
                    change.consume()
                    val w = canvasSize.width.coerceAtLeast(1).toFloat()
                    val deltaFrac = dragDelta.x / w
                    when (dragMode) {
                        DragMode.Start -> {
                            val newStart = (startState + deltaFrac).coerceIn(
                                0f, endState - minSelectionFraction)
                            onTrimChange(newStart, endState)
                        }
                        DragMode.End -> {
                            val newEnd = (endState + deltaFrac).coerceIn(
                                startState + minSelectionFraction, 1f)
                            onTrimChange(startState, newEnd)
                        }
                        DragMode.Pan -> {
                            val span = endState - startState
                            val rawStart = startState + deltaFrac
                            val newStart = rawStart.coerceIn(0f, 1f - span)
                            onTrimChange(newStart, newStart + span)
                        }
                        DragMode.None -> Unit
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())

            val startPx = startFraction * size.width
            val endPx   = endFraction   * size.width
            val handleW = 10.dp.toPx()
            val borderH = 3.dp.toPx()

            // Dim the outside-of-selection regions.
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset.Zero,
                size = Size(startPx, size.height),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(endPx, 0f),
                size = Size(size.width - endPx, size.height),
            )

            // Top + bottom yellow border on the selected range.
            drawRect(
                color = accentColor,
                topLeft = Offset(startPx, 0f),
                size = Size(endPx - startPx, borderH),
            )
            drawRect(
                color = accentColor,
                topLeft = Offset(startPx, size.height - borderH),
                size = Size(endPx - startPx, borderH),
            )

            // Left handle (yellow rounded rect with grip lines).
            drawRect(
                color = accentColor,
                topLeft = Offset(startPx - handleW / 2f, 0f),
                size = Size(handleW, size.height),
            )
            drawRect(
                color = accentColor,
                topLeft = Offset(endPx - handleW / 2f, 0f),
                size = Size(handleW, size.height),
            )
            // Grip indicator: thin white vertical line in the centre of each handle.
            drawLine(
                color = Color.White,
                start = Offset(startPx, size.height * 0.3f),
                end   = Offset(startPx, size.height * 0.7f),
                strokeWidth = 1.5.dp.toPx(),
            )
            drawLine(
                color = Color.White,
                start = Offset(endPx, size.height * 0.3f),
                end   = Offset(endPx, size.height * 0.7f),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

private enum class DragMode { None, Start, End, Pan }
