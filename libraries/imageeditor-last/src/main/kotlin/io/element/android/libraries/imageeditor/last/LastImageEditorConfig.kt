/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last

import androidx.compose.ui.graphics.Color

/**
 * Configuration for the **last** (hybrid) image editor.
 *
 * "Last" stands for the consolidation of lessons learned from the three previous attempts:
 *  - **Crop / Rotate / Flip** — uCrop (proven crop pipeline, hardware-accelerated pan/zoom).
 *  - **Drawing** — Jetpack Ink (low-latency stylus rendering) with the optimisations identified
 *    from the modular variant (offscreen Bitmap cache outside the Compose tree, incremental
 *    append-only repaint, brush size density-corrected so a "6 dp" stroke really renders at 6 dp).
 *  - **Text** — native EditText hosted via AndroidView (Compose `BasicTextField` cursor blink
 *    forced full sub-tree invalidation 2× per second), with the two-pass StaticLayout fix on
 *    export so the rendered position matches the on-screen position.
 */
data class LastImageEditorConfig(
    val outputFormat: OutputFormat = OutputFormat.JPEG,
    val outputQuality: Int = 90,
    val drawingPalette: List<Color> = DEFAULT_PALETTE,
    val strokeWidthsDp: List<Float> = listOf(2f, 6f, 12f, 24f),
    val highlighterStrokeWidthsDp: List<Float> = listOf(12f, 24f, 36f, 48f),
) {
    enum class OutputFormat { JPEG, PNG }

    companion object {
        private val DEFAULT_PALETTE = listOf(
            Color(0xFFFFFFFF),
            Color(0xFFEF5350), // red
            Color(0xFFFF9800), // orange
            Color(0xFFFFEB3B), // yellow
            Color(0xFFFDD835), // amber
            Color(0xFF66BB6A), // green
            Color(0xFF42A5F5), // blue
            Color(0xFFAB47BC), // purple
        )
    }
}

enum class LastEditorTool { None, Crop, Rotate, Draw, Highlighter, Text }
