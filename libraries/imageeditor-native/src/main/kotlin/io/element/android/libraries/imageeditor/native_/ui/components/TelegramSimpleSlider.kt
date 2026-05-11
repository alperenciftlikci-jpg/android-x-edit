/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Compact Telegram-photo-editor slider — small label on the left, a thin track that fills
 * the remaining width, and a 12 dp white knob. Matches the visual language of the Filter /
 * Tune sub-screens in the upstream Telegram app: no centre tick, no inline value readout,
 * just a clean line and a knob.
 *
 * Use this for the Tune / Effects / Filters tabs of the photo editor. The richer
 * [TelegramFilterSlider] (with centre tick and ±value badge) is still appropriate for
 * fine-grained tuning where the user benefits from seeing the numeric delta — keep both.
 */
@Composable
fun TelegramSimpleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    defaultValue: Float = range.start,
    labelWidth: androidx.compose.ui.unit.Dp = 96.dp,
) {
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    val defaultFrac = ((defaultValue - range.start) / span).coerceIn(0f, 1f)
    val isAtDefault = abs(value - defaultValue) < 0.001f

    Row(
        modifier = modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color.White.copy(alpha = if (isAtDefault) 0.7f else 0.95f),
                fontSize = 13.sp,
            ),
            modifier = Modifier.width(labelWidth),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(range, defaultValue) {
                    // awaitEachGesture handles tap (down + up) AND drag (down + moves)
                    // in one stream. detectDragGestures requires a minimum 8 dp movement
                    // before its onDragStart fires — pure taps were a no-op there,
                    // which the user perceived as "the slider doesn't work".
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = sliderSize.width.coerceAtLeast(1).toFloat()
                        // First-touch position drives an immediate snap to where the
                        // user tapped — matches Telegram's tap-anywhere-to-set behaviour.
                        onValueChange(
                            range.start + (down.position.x / w).coerceIn(0f, 1f) * span
                        )
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)
                            val change = ev.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            val f = (change.position.x / w).coerceIn(0f, 1f)
                            onValueChange(range.start + f * span)
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                sliderSize = IntSize(size.width.toInt(), size.height.toInt())
                val centreY = size.height / 2f
                val knobX = frac * size.width
                val defaultX = defaultFrac * size.width

                // Single thin white line — Telegram's filter sliders draw the whole track at
                // 30% alpha and let the knob position carry the meaning.
                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(0f, centreY),
                    end = Offset(size.width, centreY),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // Filled segment from default → knob.
                drawLine(
                    color = accentColor,
                    start = Offset(defaultX, centreY),
                    end = Offset(knobX, centreY),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // 12 dp white knob.
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(knobX, centreY),
                )
            }
        }
    }
}
