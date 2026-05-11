/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.libraries.imageeditor.native_.ui.state.TextItemEdit
import kotlin.math.hypot

/**
 * Live drag overlay for the photo editor's Text tab. Draws a small marker per text item at
 * its centre, lets the user drag any of them to reposition (in normalised image-space), and
 * tap-to-select for editing.
 *
 * The overlay only owns hit-testing + position math — the marker rectangle is rough (the
 * actual rendered text bitmap shape is owned by the native TextLayer). For a precise hit
 * region we'd need to pull each item's bitmap dimensions from the screen state; that's
 * left for a follow-up because the current 32-dp circular marker is plenty visible against
 * a photo background.
 */
@Composable
fun TelegramTextDragOverlay(
    items: List<TextItemEdit>,
    selectedId: Int?,
    onItemMove: (id: Int, x: Float, y: Float) -> Unit,
    onItemTap: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF50A8EB),
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingId by remember { mutableStateOf<Int?>(null) }

    val itemsState by rememberUpdatedState(items)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val w = canvasSize.width.coerceAtLeast(1).toFloat()
                    val h = canvasSize.height.coerceAtLeast(1).toFloat()
                    val hit = pickItem(tap, w, h, itemsState)
                    if (hit != null) onItemTap(hit.id)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val w = canvasSize.width.coerceAtLeast(1).toFloat()
                        val h = canvasSize.height.coerceAtLeast(1).toFloat()
                        draggingId = pickItem(startOffset, w, h, itemsState)?.id
                    },
                    onDragEnd = { draggingId = null },
                    onDragCancel = { draggingId = null },
                ) { change, _ ->
                    change.consume()
                    val id = draggingId ?: return@detectDragGestures
                    val w = canvasSize.width.coerceAtLeast(1).toFloat()
                    val h = canvasSize.height.coerceAtLeast(1).toFloat()
                    onItemMove(
                        id,
                        (change.position.x / w).coerceIn(0f, 1f),
                        (change.position.y / h).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            items.forEach { item ->
                val cx = item.centerX * size.width
                val cy = item.centerY * size.height
                val isSel = item.id == selectedId
                drawCircle(
                    color = if (isSel) accentColor else Color.White,
                    radius = 9.dp.toPx(),
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = 9.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

private fun pickItem(
    pos: Offset, w: Float, h: Float, items: List<TextItemEdit>,
): TextItemEdit? {
    // Pick the closest item within 48 px.
    var best: TextItemEdit? = null
    var bestDist = Float.MAX_VALUE
    items.forEach { item ->
        val cx = item.centerX * w
        val cy = item.centerY * h
        val d = hypot(pos.x - cx, pos.y - cy)
        if (d < 48f && d < bestDist) {
            best = item
            bestDist = d
        }
    }
    return best
}
