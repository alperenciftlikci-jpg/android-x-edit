/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

/**
 * Transparent canvas placed on top of the image. While [enabled] is true it captures
 * drag gestures and pushes finished strokes to [paths]. Always renders [paths]
 * regardless of [enabled] so previously drawn strokes stay visible when the user
 * switches to a different tool.
 *
 * Points are stored normalized to (0f..1f) of the canvas, so subsequent transforms
 * (crop, rotate, export to a different bitmap size) reproduce them faithfully.
 */
@Composable
fun DrawCanvas(
    paths: SnapshotStateList<DrawingPath>,
    color: Color,
    strokeWidthDp: Float,
    enabled: Boolean,
    onPathFinished: (DrawingPath) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val livePoints = remember { mutableStateListOf<Offset>() }

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { startOffset ->
                    livePoints.clear()
                    livePoints.add(startOffset.normalize(canvasSize))
                },
                onDrag = { change, _ ->
                    change.consume()
                    livePoints.add(change.position.normalize(canvasSize))
                },
                onDragEnd = {
                    if (livePoints.size > 1) {
                        onPathFinished(
                            DrawingPath(
                                points = livePoints.toList(),
                                color = color,
                                strokeWidthDp = strokeWidthDp,
                            )
                        )
                    }
                    livePoints.clear()
                },
                onDragCancel = { livePoints.clear() },
            )
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .then(gestureModifier),
    ) {
        canvasSize = IntSize(size.width.toInt(), size.height.toInt())

        // Draw committed paths.
        paths.forEach { p ->
            drawDrawingPath(p, density.density, size.width, size.height)
        }

        // Draw the in-progress path.
        if (livePoints.size > 1) {
            val ongoing = DrawingPath(
                points = livePoints.toList(),
                color = color,
                strokeWidthDp = strokeWidthDp,
            )
            drawDrawingPath(ongoing, density.density, size.width, size.height)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDrawingPath(
    path: DrawingPath,
    densityScale: Float,
    width: Float,
    height: Float,
) {
    if (path.points.size < 2) return
    val androidPath = Path().apply {
        val first = path.points.first()
        moveTo(first.x * width, first.y * height)
        for (i in 1 until path.points.size) {
            val p = path.points[i]
            lineTo(p.x * width, p.y * height)
        }
    }
    drawPath(
        path = androidPath,
        color = path.color,
        style = Stroke(
            width = path.strokeWidthDp * densityScale,
            cap = StrokeCap.Round,
        ),
    )
}

private fun Offset.normalize(size: IntSize): Offset {
    val w = size.width.coerceAtLeast(1).toFloat()
    val h = size.height.coerceAtLeast(1).toFloat()
    return Offset(
        (x / w).coerceIn(0f, 1f),
        (y / h).coerceIn(0f, 1f),
    )
}
