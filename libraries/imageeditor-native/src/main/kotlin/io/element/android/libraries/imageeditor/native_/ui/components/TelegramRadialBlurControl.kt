/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.libraries.imageeditor.native_.BlurType
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Telegram-style overlay for the radial / linear focus blur. Drawn on top of the photo
 * preview when the user is on the Blur tab.
 *
 *   - **Radial**: two concentric circles (inner = sharp, outer = blur edge). Drag the centre
 *     to reposition; drag the inner ring's edge to resize the inner; drag the outer ring's
 *     edge to resize the outer.
 *   - **Linear**: two parallel lines through the centre. Drag the centre to reposition; drag
 *     either line to change the inner/outer offset; drag along the lines to rotate.
 *
 * All coords are normalised image-space (0..1). The component owns a hit-test policy that
 * picks the most-natural target from the touch position.
 */
@Composable
fun TelegramRadialBlurControl(
    type: BlurType,
    centerX: Float,
    centerY: Float,
    innerRadius: Float,
    outerRadius: Float,
    angleRadians: Float,
    onCenterChange: (Float, Float) -> Unit,
    onInnerRadiusChange: (Float) -> Unit,
    onOuterRadiusChange: (Float) -> Unit,
    onAngleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (type == BlurType.Off) return

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragMode by remember { mutableStateOf(DragMode.None) }

    // Always-fresh refs so the gesture coroutine can read the latest state.
    val centreXState by rememberUpdatedState(centerX)
    val centreYState by rememberUpdatedState(centerY)
    val innerRState  by rememberUpdatedState(innerRadius)
    val outerRState  by rememberUpdatedState(outerRadius)
    val angleState   by rememberUpdatedState(angleRadians)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(type) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        // Decide what the user grabbed — centre, inner ring, or outer ring.
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val h = canvasSize.height.coerceAtLeast(1).toFloat()
                        val cx = centreXState * w
                        val cy = centreYState * h
                        val touchDist = hypot(startOffset.x - cx, startOffset.y - cy)
                        val innerPx   = innerRState * minOf(w, h)
                        val outerPx   = outerRState * minOf(w, h)
                        val touchTolPx = 36f
                        dragMode = when {
                            touchDist < innerPx - touchTolPx -> DragMode.Centre
                            kotlin.math.abs(touchDist - innerPx) < touchTolPx -> DragMode.InnerRing
                            kotlin.math.abs(touchDist - outerPx) < touchTolPx -> DragMode.OuterRing
                            else -> DragMode.Centre
                        }
                    },
                    onDragEnd = { dragMode = DragMode.None },
                    onDragCancel = { dragMode = DragMode.None },
                ) { change, _ ->
                    change.consume()
                    val w = canvasSize.width.coerceAtLeast(1).toFloat()
                    val h = canvasSize.height.coerceAtLeast(1).toFloat()
                    val cx = centreXState * w
                    val cy = centreYState * h
                    when (dragMode) {
                        DragMode.Centre -> {
                            onCenterChange(
                                (change.position.x / w).coerceIn(0f, 1f),
                                (change.position.y / h).coerceIn(0f, 1f),
                            )
                        }
                        DragMode.InnerRing -> {
                            val touchDist = hypot(change.position.x - cx, change.position.y - cy)
                            val newInner = (touchDist / minOf(w, h)).coerceIn(0.05f, outerRState - 0.02f)
                            onInnerRadiusChange(newInner)
                        }
                        DragMode.OuterRing -> {
                            val touchDist = hypot(change.position.x - cx, change.position.y - cy)
                            val newOuter = (touchDist / minOf(w, h)).coerceIn(innerRState + 0.02f, 0.9f)
                            onOuterRadiusChange(newOuter)
                        }
                        DragMode.None -> Unit
                    }
                }
            },
    ) {
        canvasSize = IntSize(size.width.toInt(), size.height.toInt())
        val cx = centerX * size.width
        val cy = centerY * size.height
        val unit = minOf(size.width, size.height)
        val innerPx = innerRadius * unit
        val outerPx = outerRadius * unit

        when (type) {
            BlurType.Radial -> {
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = innerPx,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.45f),
                    radius = outerPx,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            BlurType.Linear -> {
                val dirX = cos(angleRadians)
                val dirY = sin(angleRadians)
                val perpX = -dirY
                val perpY = dirX
                val len = unit * 1.5f      // long enough to reach screen edges
                fun drawParallel(offset: Float, alpha: Float) {
                    val ox = perpX * offset
                    val oy = perpY * offset
                    drawLine(
                        color = Color.White.copy(alpha = alpha),
                        start = Offset(cx + ox - dirX * len, cy + oy - dirY * len),
                        end   = Offset(cx + ox + dirX * len, cy + oy + dirY * len),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                drawParallel(+innerPx, 0.85f)
                drawParallel(-innerPx, 0.85f)
                drawParallel(+outerPx, 0.45f)
                drawParallel(-outerPx, 0.45f)
            }
            BlurType.Off -> Unit
        }

        // Centre marker — small circle for affordance.
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = Offset(cx, cy),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.4f),
            radius = 6.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private enum class DragMode { None, Centre, InnerRing, OuterRing }
