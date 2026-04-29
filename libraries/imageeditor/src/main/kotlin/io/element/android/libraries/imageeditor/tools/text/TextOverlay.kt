/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.text

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Overlay that renders draggable text items on top of the image. When [enabled] is
 * true items can be dragged; [onTextTap] is invoked on tap for editing/removing.
 *
 * Item positions are stored as normalized top-left coordinates (0f..1f) so they
 * survive transforms.
 */
@Composable
fun TextOverlay(
    items: SnapshotStateList<TextItem>,
    enabled: Boolean,
    onItemMoved: (TextItem) -> Unit,
    onTextTap: (TextItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        items.forEach { item ->
            // Keep a ref that always reads the latest item from state. Without this
            // the gesture coroutine captures the item at first composition and every
            // drag delta is added to the same stale position, producing a jitter.
            val currentItem by rememberUpdatedState(item)
            val onMoved by rememberUpdatedState(onItemMoved)
            val onTap by rememberUpdatedState(onTextTap)

            val offsetX = (item.position.x * canvasSize.width).roundToInt()
            val offsetY = (item.position.y * canvasSize.height).roundToInt()

            val gestureMod = if (enabled) {
                Modifier
                    .pointerInput(item.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val w = canvasSize.width.coerceAtLeast(1)
                            val h = canvasSize.height.coerceAtLeast(1)
                            val latest = currentItem
                            val newPos = Offset(
                                (latest.position.x + drag.x / w).coerceIn(0f, 1f),
                                (latest.position.y + drag.y / h).coerceIn(0f, 1f),
                            )
                            onMoved(latest.copy(position = newPos))
                        }
                    }
                    .pointerInput(item.id) {
                        detectTapGestures(onTap = { onTap(currentItem) })
                    }
            } else {
                Modifier
            }

            val backgroundColor = when (item.backgroundMode) {
                TextBackgroundMode.None -> Color.Transparent
                TextBackgroundMode.Solid -> item.backgroundColor
                TextBackgroundMode.SemiTransparent -> item.backgroundColor.copy(alpha = 0.5f)
            }

            Text(
                text = item.text,
                color = item.color,
                style = TextStyle(
                    fontSize = item.fontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = item.align,
                ),
                modifier = Modifier
                    .offset { IntOffset(offsetX, offsetY) }
                    .then(gestureMod)
                    .clip(RoundedCornerShape(6.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
