/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Telegram-style horizontal filter slider.
 *
 * Looks like:
 *
 *   Brightness                     +25
 *   ──────────────────│──────●─────────
 *                     ↑      ↑
 *                  centre  handle
 *
 * `range` is symmetric — usually `-1f..1f` (mapped to -100..+100 for display) or
 * `0f..1f` for "amount" sliders. The centre tick is drawn at `defaultValue`.
 *
 * Drag is implemented with `detectDragGestures` on a transparent overlay so the
 * touch surface is the entire slider row, not just the 2 dp track.
 */
@Composable
fun TelegramFilterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    displayScale: Float = 100f,                // value × displayScale → integer label
    accentColor: Color = Color(0xFF50A8EB),    // Telegram blue
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    val displayValue = (value * displayScale).roundToInt()

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        // Label row: name on the left, signed integer value on the right.
        Row(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = if (displayValue > 0) "+$displayValue" else "$displayValue",
                style = TextStyle(
                    color = if (displayValue == 0) Color.White.copy(alpha = 0.5f) else accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))

        // Slider canvas + drag layer.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(range, defaultValue) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            // Tap-to-move: jump the handle to where the user touched
                            // before drag deltas accumulate.
                            val w = sliderSize.width.coerceAtLeast(1).toFloat()
                            val frac = (startOffset.x / w).coerceIn(0f, 1f)
                            onValueChange(range.start + (range.endInclusive - range.start) * frac)
                        },
                    ) { change, _ ->
                        change.consume()
                        val w = sliderSize.width.coerceAtLeast(1).toFloat()
                        val frac = (change.position.x / w).coerceIn(0f, 1f)
                        onValueChange(range.start + (range.endInclusive - range.start) * frac)
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            ) {
                sliderSize = IntSize(size.width.toInt(), size.height.toInt())

                val centreY = size.height / 2f
                val span = range.endInclusive - range.start
                val handleX = ((value - range.start) / span).coerceIn(0f, 1f) * size.width
                val defaultX = ((defaultValue - range.start) / span).coerceIn(0f, 1f) * size.width

                // Track: left of handle (filled with accent), right (white 30%).
                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(0f, centreY),
                    end   = Offset(size.width, centreY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accentColor,
                    start = Offset(defaultX, centreY),
                    end   = Offset(handleX, centreY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Centre tick at the default-value position.
                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(defaultX, centreY - 6.dp.toPx()),
                    end   = Offset(defaultX, centreY + 6.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )

                // Handle: 18 dp white disc with a thin shadow ring for affordance.
                drawCircle(
                    color = Color.Black.copy(alpha = 0.25f),
                    radius = 11.dp.toPx(),
                    center = Offset(handleX, centreY),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = Offset(handleX, centreY),
                )
            }
        }
    }
}
