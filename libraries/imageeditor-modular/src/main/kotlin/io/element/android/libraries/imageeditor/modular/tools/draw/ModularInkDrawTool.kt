/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.modular.tools.draw

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
 * Drawing tool wrapping Jetpack Ink's `InProgressStrokes` Compose composable.
 *
 * `InProgressStrokes` handles its own low-latency input rendering on a SurfaceView under the hood;
 * once a stroke completes, the [Stroke] object is handed to [onStrokeFinished] for storage.
 *
 * **Why committed strokes live in a custom Android View, not a Compose `Canvas`**.
 *
 * The natural implementation paints committed strokes inside a Compose `Canvas` (or
 * `Modifier.drawWithCache`) sibling to `InProgressStrokes`. We tried that. Two interactions made
 * the gesture's frame rate collapse to ~16 fps:
 *
 *  1. `inkStrokes` is a `SnapshotStateList`. Reading it inside a `Canvas`/`drawWithCache` block
 *     re-runs the entire draw lambda on every Compose recomposition of the parent — and the
 *     parent recomposes a lot during a stroke (state holders flowing through, etc.).
 *  2. `InProgressStrokes` mounts a `SurfaceView`. Its z-order shuffle while a gesture is in
 *     progress invalidates the Compose layer underneath at input rate, forcing whatever Compose
 *     `Canvas` siblings exist to redraw.
 *
 * Mounting a plain Android `View` ([CommittedStrokesView]) via `AndroidView` puts the committed
 * layer outside the Compose draw tree entirely. It rasterises once into a cached `Bitmap` when
 * `inkStrokes` actually changes, and per-frame cost during a live gesture drops to a single
 * `drawBitmap` blit.
 */
@Composable
fun ModularInkDrawTool(
    brush: Brush,
    committedStrokes: List<Stroke>,
    enabled: Boolean,
    onStrokeFinished: (Stroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberUpdatedState so the lambda passed to InProgressStrokes always reads the freshest
    // brush — without this, a colour/width change between strokes wouldn't take effect.
    val currentBrush by rememberUpdatedState(brush)
    // TracedGesture observes touch lifecycle alongside InProgressStrokes so we get both the Ink
    // metadata (via onStrokesFinished) AND a real frame-aware FPS reading. We don't consume the
    // pointer event so InProgressStrokes still receives input normally.
    val strokeGesture = remember { TracedGesture("imageeditor.modular.draw.stroke") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        // Initial pass = before children. We just observe; not consuming means
                        // InProgressStrokes' SurfaceView still gets the event for live drawing.
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
        // Committed strokes layer. Lives outside the Compose tree — see class kdoc on
        // CommittedStrokesView for the rationale.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> CommittedStrokesView(ctx) },
            update = { view -> view.setStrokes(committedStrokes) },
        )

        // Live capture surface — only when this tool is the active one. When `enabled` is false
        // the InProgressStrokes is not composed, which lets the underlying gesture detection
        // (text overlay / crop view) receive touches normally.
        if (enabled) {
            InProgressStrokes(
                defaultBrush = currentBrush,
                nextBrush = { currentBrush },
                onStrokesFinished = { strokes: List<Stroke> ->
                    // FPS already captured by TracedGesture above; here we just hand the stroke
                    // off for committed-stroke storage so it keeps rendering after the surface clears.
                    strokes.forEach { stroke -> onStrokeFinished(stroke) }
                },
            )
        }
    }
}
