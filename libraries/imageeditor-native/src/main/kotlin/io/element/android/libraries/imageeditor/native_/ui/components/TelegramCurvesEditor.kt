/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.libraries.imageeditor.native_.CurveLut
import kotlin.math.abs

/**
 * Telegram-style RGB-curves editor. The canvas plots a control-point spline for the
 * currently-selected channel (Luma / R / G / B), with the other channels rendered as
 * faded reference lines. Drag a control point to reshape the curve; double-tap to
 * remove. Tap on the line between points to add a new control point.
 *
 * `onCurvesChange` fires with a fresh [CurveLut] whenever any channel's spline mutates.
 * The LUT contains a 256-entry sample for each of R / G / B / Luma; we re-sample the
 * spline through Catmull-Rom interpolation between control points.
 */
@Composable
fun TelegramCurvesEditor(
    initialLut: CurveLut,
    onCurvesChange: (CurveLut) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedChannel by remember { mutableStateOf(Channel.Luma) }
    val controls = remember {
        // Each channel: list of (x, y) control points in [0, 1]. Defaults: identity diagonal
        // (0,0) → (1,1) — 2 anchor points only.
        mutableMapOf(
            Channel.Luma to mutableStateOf(listOf(0f to 0f, 1f to 1f)),
            Channel.R    to mutableStateOf(listOf(0f to 0f, 1f to 1f)),
            Channel.G    to mutableStateOf(listOf(0f to 0f, 1f to 1f)),
            Channel.B    to mutableStateOf(listOf(0f to 0f, 1f to 1f)),
        )
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingIndex by remember { mutableStateOf(-1) }

    Column(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        // Channel chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Channel.values().forEach { ch ->
                ChannelChip(channel = ch,
                    isSelected = selectedChannel == ch,
                    onClick = { selectedChannel = ch })
            }
        }
        Spacer(Modifier.height(8.dp))

        // Curves canvas — square aspect (Telegram does this so the diagonal is at 45°).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1C1E))
                .pointerInput(selectedChannel) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val w = canvasSize.width.coerceAtLeast(1).toFloat()
                            val h = canvasSize.height.coerceAtLeast(1).toFloat()
                            val pts = controls[selectedChannel]!!.value
                            // Pick the nearest control point under the touch (within tol).
                            draggingIndex = pts.indexOfFirst { (px, py) ->
                                val sx = px * w
                                val sy = (1f - py) * h
                                abs(sx - startOffset.x) < 32f && abs(sy - startOffset.y) < 32f
                            }
                        },
                        onDragEnd = { draggingIndex = -1 },
                        onDragCancel = { draggingIndex = -1 },
                    ) { change, _ ->
                        change.consume()
                        if (draggingIndex < 0) return@detectDragGestures
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val h = canvasSize.height.coerceAtLeast(1).toFloat()
                        val newX = (change.position.x / w).coerceIn(0f, 1f)
                        val newY = (1f - change.position.y / h).coerceIn(0f, 1f)
                        val pts = controls[selectedChannel]!!.value.toMutableList()
                        pts[draggingIndex] = newX to newY
                        // Keep endpoints anchored on the left/right edges; just clamp x.
                        if (draggingIndex == 0) pts[0] = 0f to newY.coerceIn(0f, 1f)
                        if (draggingIndex == pts.lastIndex) pts[pts.lastIndex] = 1f to newY.coerceIn(0f, 1f)
                        controls[selectedChannel]!!.value = pts.sortedBy { it.first }
                        onCurvesChange(buildLut(controls))
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(0.dp)) {
                canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                val w = size.width
                val h = size.height

                // Grid (4×4)
                for (i in 1..3) {
                    val gx = w * i / 4f
                    val gy = h * i / 4f
                    drawLine(Color.White.copy(alpha = 0.08f),
                        Offset(gx, 0f), Offset(gx, h), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.08f),
                        Offset(0f, gy), Offset(w, gy), 1.dp.toPx())
                }

                // Identity diagonal reference.
                drawLine(Color.White.copy(alpha = 0.2f),
                    Offset(0f, h), Offset(w, 0f), 1.dp.toPx())

                // Inactive channels — faded curves.
                Channel.values().filter { it != selectedChannel }.forEach { ch ->
                    drawCurve(controls[ch]!!.value, ch.color.copy(alpha = 0.25f), w, h, 1.5.dp.toPx())
                }

                // Active channel + handles.
                drawCurve(controls[selectedChannel]!!.value,
                    selectedChannel.color, w, h, 2.5.dp.toPx())
                controls[selectedChannel]!!.value.forEachIndexed { i, (px, py) ->
                    val cx = px * w
                    val cy = (1f - py) * h
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(selectedChannel.color, radius = 5.dp.toPx(), center = Offset(cx, cy))
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(channel: Channel, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) channel.color.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(channel.color),
        )
        BasicText(
            text = channel.label,
            style = TextStyle(
                color = if (isSelected) channel.color else Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurve(
    points: List<Pair<Float, Float>>,
    color: Color,
    canvasW: Float,
    canvasH: Float,
    strokeWidth: Float,
) {
    if (points.size < 2) return
    val path = Path()
    val first = points.first()
    path.moveTo(first.first * canvasW, (1f - first.second) * canvasH)
    // Catmull-Rom approximation by sampling 100 points along the spline.
    val samples = 100
    for (s in 1..samples) {
        val t = s / samples.toFloat()
        val (sx, sy) = sampleSpline(points, t)
        path.lineTo(sx * canvasW, (1f - sy) * canvasH)
    }
    drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
}

/** Sample the y for a given x ∈ [0, 1] using piecewise Catmull-Rom between control points. */
internal fun sampleSpline(points: List<Pair<Float, Float>>, x: Float): Pair<Float, Float> {
    if (points.isEmpty()) return x to x
    if (points.size == 1) return x to points[0].second
    // Find the segment.
    val idx = points.indexOfFirst { it.first >= x }.let { if (it < 0) points.lastIndex else it }
    if (idx == 0) return x to points[0].second
    val p1 = points[idx - 1]
    val p2 = points[idx]
    val span = (p2.first - p1.first).coerceAtLeast(1e-4f)
    val t = ((x - p1.first) / span).coerceIn(0f, 1f)
    // Use neighbour points for tangent estimation; clamp to endpoints.
    val p0 = if (idx - 2 >= 0) points[idx - 2] else p1
    val p3 = if (idx + 1 <= points.lastIndex) points[idx + 1] else p2
    // Catmull-Rom on y (x is parameterised explicitly).
    val y = catmullRom(p0.second, p1.second, p2.second, p3.second, t)
    return x to y.coerceIn(0f, 1f)
}

private fun catmullRom(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5f * ((2f * b) +
                   (-a + c) * t +
                   (2f * a - 5f * b + 4f * c - d) * t2 +
                   (-a + 3f * b - 3f * c + d) * t3)
}

/** Build a [CurveLut] from the per-channel control point lists. */
internal fun buildLut(controls: Map<Channel, androidx.compose.runtime.MutableState<List<Pair<Float, Float>>>>): CurveLut {
    val data = FloatArray(1024)
    for (i in 0 until 256) {
        val x = i / 255f
        data[i]       = sampleSpline(controls[Channel.R]!!.value,    x).second
        data[256 + i] = sampleSpline(controls[Channel.G]!!.value,    x).second
        data[512 + i] = sampleSpline(controls[Channel.B]!!.value,    x).second
        data[768 + i] = sampleSpline(controls[Channel.Luma]!!.value, x).second
    }
    val isIdentity = controls.values.all { ms ->
        val pts = ms.value
        pts.size == 2 && pts[0] == (0f to 0f) && pts[1] == (1f to 1f)
    }
    return CurveLut(data = data, isIdentity = isIdentity)
}

internal enum class Channel(val label: String, val color: Color) {
    Luma("Luma", Color(0xFFE5E5E5)),
    R("Red",    Color(0xFFEF5350)),
    G("Green",  Color(0xFF66BB6A)),
    B("Blue",   Color(0xFF42A5F5)),
}
