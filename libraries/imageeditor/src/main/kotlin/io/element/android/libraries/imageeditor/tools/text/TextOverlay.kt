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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.element.android.libraries.core.perf.TracedGesture
import io.element.android.libraries.core.perf.trace
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
    val density = LocalDensity.current

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

            val moveGesture = remember(item.id) { TracedGesture("imageeditor.text.move") }
            // Local "live" offset used during a drag. While set, it overrides currentItem.position
            // for layout. We commit to the outer SnapshotStateList only at drag-end. This avoids
            // rewriting the SnapshotStateList on every drag delta — a write there propagates to
            // the parent `items.forEach` block and re-runs *all* text Composables for every
            // 1-pixel finger movement, which is what kept this gesture at ~52 fps.
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
                            trace("imageeditor.text.tap") { onTap(currentItem) }
                        })
                    }
            } else {
                Modifier
            }

            val backgroundColor = when (item.backgroundMode) {
                TextBackgroundMode.None -> Color.Transparent
                TextBackgroundMode.Solid -> item.backgroundColor
                TextBackgroundMode.SemiTransparent -> item.backgroundColor.copy(alpha = 0.5f)
            }

            // BasicText (vs Material3 `Text`) skips the LocalContentColor / LocalTextStyle reads
            // and the typography theming pipeline. We use `Modifier.offset` *lambda overload* —
            // not `graphicsLayer { translationX/Y }`. graphicsLayer is faster (skips layout
            // pass) but moves only the rendering: hit testing stays at the original layout
            // position (0,0). That meant after a drag, the text was visually in one place but
            // the touch target was still in the upper-left corner — pressing on the text did
            // nothing (or hit the wrong overlapping item), and `item.position` got committed
            // off from where the user thought they placed it.
            //
            // The lambda overload reads `liveOffset` / `currentItem.position` inside the layout
            // phase, so layout placement *and* hit testing both move with the text. State
            // changes during a drag invalidate only this Text's layout pass — composition is
            // not retriggered, the parent `items.forEach` is unaffected. Performance loss
            // vs graphicsLayer is negligible here because Box parent is fillMaxSize and layout
            // for a single Text node is essentially free.
            // Wrap kuralı export'la aynı: text'in TL'i (pos.x * canvasW) noktasında, padding her
            // iki yandan 8 dp. Yani text içeriği (canvasW - TL.x - 2*8dp) genişliğine kadar
            // sığabilir; daha uzun olursa wrap eder. Aynı kural BitmapExporter.drawTexts'te
            // `contentMaxWidth = w - blockLeft - 2 * padX`. widthIn(max = ...) ile Compose
            // tarafına aynı sınırı uygulayarak canvas'ta görünen wrap noktası ile bitmap'in
            // wrap noktası birebir aynı olur.
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
