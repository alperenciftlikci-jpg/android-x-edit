/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last.tools.text

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.element.android.libraries.core.perf.TracedGesture
import io.element.android.libraries.core.perf.trace
import io.element.android.libraries.imageeditor.last.LastTextBackgroundMode
import io.element.android.libraries.imageeditor.last.LastTextItem
import kotlin.math.roundToInt

/**
 * Draggable text overlay. Position is normalized to the canvas (0..1 each axis) and stored on
 * the `LastTextItem` itself.
 *
 * Drag pattern: a local `liveOffset` mutates per-event; the outer `SnapshotStateList` is only
 * written to on drag end — this keeps the parent `items.forEach` from re-running for every 1-px
 * finger movement.
 *
 * `Modifier.offset` lambda overload (not `graphicsLayer`) — see the rationale in the original
 * baseline `TextOverlay`: graphicsLayer breaks hit testing because translation moves only the
 * rendering, not the layout bounds. With `offset`, both the visual position and the touch hit
 * area follow the drag.
 *
 * `widthIn(max = ...)` mirrors the export pipeline's wrap point (`bitmap_w - blockLeft - 2*padX`)
 * so a long string wraps at the same place on canvas as it does in the rendered bitmap.
 */
@Composable
fun LastTextOverlay(
    items: SnapshotStateList<LastTextItem>,
    enabled: Boolean,
    onItemMoved: (LastTextItem) -> Unit,
    onTextTap: (LastTextItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        items.forEach { item ->
            val currentItem by rememberUpdatedState(item)
            val onMoved by rememberUpdatedState(onItemMoved)
            val onTap by rememberUpdatedState(onTextTap)

            val moveGesture = remember(item.id) { TracedGesture("imageeditor.last.text.move") }
            var liveOffset by remember(item.id) { mutableStateOf<Offset?>(null) }
            val gestureMod = if (enabled) {
                Modifier
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = {
                                liveOffset = currentItem.position
                                moveGesture.start()
                            },
                            onDragEnd = {
                                val finalPos = liveOffset ?: currentItem.position
                                liveOffset = null
                                onMoved(currentItem.copy(position = finalPos))
                                moveGesture.finish()
                            },
                            onDragCancel = {
                                liveOffset = null
                                moveGesture.cancel()
                            },
                        ) { change, drag ->
                            change.consume()
                            val w = canvasSize.width.coerceAtLeast(1)
                            val h = canvasSize.height.coerceAtLeast(1)
                            val cur = liveOffset ?: currentItem.position
                            liveOffset = Offset(
                                (cur.x + drag.x / w).coerceIn(0f, 1f),
                                (cur.y + drag.y / h).coerceIn(0f, 1f),
                            )
                        }
                    }
                    .pointerInput(item.id) {
                        detectTapGestures(onTap = {
                            trace("imageeditor.last.text.tap") { onTap(currentItem) }
                        })
                    }
            } else {
                Modifier
            }

            val backgroundColor = when (item.backgroundMode) {
                LastTextBackgroundMode.None -> Color.Transparent
                LastTextBackgroundMode.Solid -> item.backgroundColor
                LastTextBackgroundMode.SemiTransparent -> item.backgroundColor.copy(alpha = 0.5f)
            }

            val pos = liveOffset ?: currentItem.position
            val maxWidthDp = with(density) {
                (canvasSize.width - (pos.x * canvasSize.width).roundToInt())
                    .coerceAtLeast(1)
                    .toDp() - 16.dp
            }
            BasicText(
                text = item.text,
                style = TextStyle(
                    color = item.color,
                    fontSize = item.fontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = item.align,
                ),
                modifier = Modifier
                    .offset {
                        val p = liveOffset ?: currentItem.position
                        IntOffset(
                            (p.x * canvasSize.width).roundToInt(),
                            (p.y * canvasSize.height).roundToInt(),
                        )
                    }
                    .then(gestureMod)
                    .widthIn(max = maxWidthDp.coerceAtLeast(1.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
