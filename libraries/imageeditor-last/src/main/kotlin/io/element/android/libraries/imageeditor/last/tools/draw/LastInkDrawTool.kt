/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last.tools.draw

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import io.element.android.libraries.core.perf.TracedGesture

/**
 * Drawing tool for the hybrid editor — Jetpack Ink's `InProgressStrokes` for the live (touch
 * down → up) stroke, and a custom Android `View` ([LastCommittedStrokesView]) under it for the
 * committed strokes layer.
 *
 * See `LastCommittedStrokesView` kdoc for why we keep the committed layer outside the Compose
 * tree.
 *
 * `Brush.size` is now in **pixels** (the state holder applies `dp × density` when it constructs
 * the Brush). This means `CanvasStrokeRenderer.draw(...)` with the identity matrix renders
 * strokes at the user-picked dp width. The bitmap exporter mirrors this with `brush.size × sx`
 * — no extra density factor.
 */
@Composable
fun LastInkDrawTool(
    brush: Brush,
    committedStrokes: List<Stroke>,
    enabled: Boolean,
    onStrokeFinished: (Stroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentBrush by rememberUpdatedState(brush)
    val strokeGesture = remember { TracedGesture("imageeditor.last.draw.stroke") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        // Initial pass: observe before children. Not consuming → InProgressStrokes
                        // still receives the event for live drawing.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when {
                            change.changedToDown() -> strokeGesture.start()
                            change.changedToUp() -> strokeGesture.finish()
                        }
                    }
                }
            },
    ) {
        // Committed strokes layer — outside Compose tree.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> LastCommittedStrokesView(ctx) },
            update = { view -> view.setStrokes(committedStrokes) },
        )

        // Live capture surface — only mounted while the tool is active so other tools' touches
        // pass through to the gesture detectors below.
        if (enabled) {
            InProgressStrokes(
                defaultBrush = currentBrush,
                nextBrush = { currentBrush },
                onStrokesFinished = { strokes: List<Stroke> ->
                    strokes.forEach { stroke -> onStrokeFinished(stroke) }
                },
            )
        }
    }
}
