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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Telegram-style trim selector — yellow handles centred on the trim boundary, dim black
 * overlay on the unselected regions, white draggable play-head inside the selection.
 *
 * Hit zones (density-aware, so the same physical mm on every screen):
 *  - 36 dp slop around each handle → grabs Start / End
 *  - 24 dp slop around the play-head → scrubs playback
 *  - Anywhere else inside the selection → starts a scrub (play-head jumps to touch)
 *
 * Visual mirrors `org.telegram.ui.Components.VideoTimelinePlayView`: 0xFFFFFF00 yellow,
 * chunky 16 dp handles with 4 dp corner rounding, two parallel dark grip bars per handle,
 * 0x99 black dim over the unselected segments, bold white play-head pill.
 */
@Composable
fun TelegramTrimHandles(
    startFraction: Float,
    endFraction: Float,
    onTrimChange: (start: Float, end: Float) -> Unit,
    modifier: Modifier = Modifier,
    playProgress: Float? = null,
    onPlayProgressChange: ((Float) -> Unit)? = null,
    accentColor: Color = Color(0xFFFFFF00),
    minSelectionFraction: Float = 0.02f,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.None) }

    val startState by rememberUpdatedState(startFraction)
    val endState   by rememberUpdatedState(endFraction)
    val playState  by rememberUpdatedState(playProgress)
    val onScrubState by rememberUpdatedState(onPlayProgressChange)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                // Density-aware tolerances — chunky physical targets, ≈12 mm on handles
                // and ≈8 mm on the play-head at 3x density.
                val handleTolerancePx = 36.dp.toPx()
                val playTolerancePx = 24.dp.toPx()
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val touchX = startOffset.x
                        val startPx = startState * w
                        val endPx   = endState * w
                        val playPx  = playState?.let { it * w }
                        val canScrub = onScrubState != null

                        // Priority: trim handles first (sit at the edges of the selection),
                        // then play-head, then anywhere inside selection as a scrub.
                        dragMode = when {
                            abs(touchX - startPx) < handleTolerancePx -> DragMode.Start
                            abs(touchX - endPx) < handleTolerancePx   -> DragMode.End
                            canScrub && playPx != null &&
                                abs(touchX - playPx) < playTolerancePx -> DragMode.PlayHead
                            canScrub && touchX > startPx && touchX < endPx -> {
                                // Touch anywhere inside the selection ⇒ play-head jumps to
                                // that spot, then subsequent drag deltas refine. This is
                                // how Telegram's video editor lets you scrub instantly.
                                val targetFrac = (touchX / w).coerceIn(startState, endState)
                                onScrubState?.invoke(targetFrac)
                                DragMode.PlayHead
                            }
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
                            val newStart = (startState + deltaFrac)
                                .coerceIn(0f, endState - minSelectionFraction)
                            onTrimChange(newStart, endState)
                        }
                        DragMode.End -> {
                            val newEnd = (endState + deltaFrac)
                                .coerceIn(startState + minSelectionFraction, 1f)
                            onTrimChange(startState, newEnd)
                        }
                        DragMode.PlayHead -> {
                            val current = playState ?: ((startState + endState) / 2f)
                            val newProgress = (current + deltaFrac)
                                .coerceIn(startState, endState)
                            onScrubState?.invoke(newProgress)
                        }
                        DragMode.None -> Unit
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val startPx = startFraction * w
            val endPx   = endFraction   * w
            val handleW = 16.dp.toPx()
            val borderH = 3.dp.toPx()
            val handleR = CornerRadius(4.dp.toPx())

            // Dim the outside-of-selection regions (~60% black, matches Telegram).
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = Offset.Zero,
                size = Size(startPx, h),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = Offset(endPx, 0f),
                size = Size(w - endPx, h),
            )

            // Yellow top + bottom borders along the selected range.
            drawRect(
                color = accentColor,
                topLeft = Offset(startPx, 0f),
                size = Size(endPx - startPx, borderH),
            )
            drawRect(
                color = accentColor,
                topLeft = Offset(startPx, h - borderH),
                size = Size(endPx - startPx, borderH),
            )

            // Chunky yellow handles, centred on the trim boundary with 4 dp rounding.
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(startPx - handleW / 2f, 0f),
                size = Size(handleW, h),
                cornerRadius = handleR,
            )
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(endPx - handleW / 2f, 0f),
                size = Size(handleW, h),
                cornerRadius = handleR,
            )

            // Two parallel dark grip bars per handle — the Telegram fingerprint.
            val gripW = 1.5.dp.toPx()
            val gripH = h * 0.45f
            val gripTop = (h - gripH) / 2f
            val gripGap = 3.dp.toPx()
            val gripColor = Color.Black.copy(alpha = 0.75f)
            fun drawGrip(centerX: Float) {
                drawRect(
                    color = gripColor,
                    topLeft = Offset(centerX - gripGap / 2f - gripW, gripTop),
                    size = Size(gripW, gripH),
                )
                drawRect(
                    color = gripColor,
                    topLeft = Offset(centerX + gripGap / 2f, gripTop),
                    size = Size(gripW, gripH),
                )
            }
            drawGrip(startPx)
            drawGrip(endPx)

            // Play-head: bold white pill inside the selection, rounded ends for clarity.
            playState?.let { p ->
                if (p in startFraction..endFraction) {
                    val playX = p * w
                    val playW = 3.dp.toPx()
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(playX - playW / 2f, borderH),
                        size = Size(playW, h - borderH * 2f),
                        cornerRadius = CornerRadius(playW / 2f),
                    )
                }
            }
        }
    }
}

private enum class DragMode { None, Start, End, PlayHead }
