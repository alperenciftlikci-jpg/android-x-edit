/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.imageeditor.modular

import androidx.compose.ui.graphics.Color

/**
 * Configuration for the modular editor. Mirrors the shape of the baseline
 * `ImageEditorConfig` so a user can swap implementations without touching call sites.
 */
data class ModularImageEditorConfig(
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

enum class ModularEditorTool { None, Crop, Rotate, Draw, Highlighter, Text }
