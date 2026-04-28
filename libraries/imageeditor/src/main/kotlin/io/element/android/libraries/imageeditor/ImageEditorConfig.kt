/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Aspect ratio preset offered to the user when cropping.
 *
 * @param numerator width portion (or null for free / original)
 * @param denominator height portion
 * @param label short label shown in the UI (e.g. "1:1", "Free")
 */
@Immutable
data class AspectRatioPreset(
    val numerator: Float?,
    val denominator: Float?,
    val label: String,
) {
    val ratio: Float? = if (numerator != null && denominator != null && denominator > 0f) {
        numerator / denominator
    } else {
        null
    }

    companion object {
        val Free = AspectRatioPreset(null, null, "Free")
        val Original = AspectRatioPreset(null, null, "Original")
        val Square = AspectRatioPreset(1f, 1f, "1:1")
        val FourByThree = AspectRatioPreset(4f, 3f, "4:3")
        val ThreeByFour = AspectRatioPreset(3f, 4f, "3:4")
        val SixteenByNine = AspectRatioPreset(16f, 9f, "16:9")
        val NineBySixteen = AspectRatioPreset(9f, 16f, "9:16")

        val Defaults: List<AspectRatioPreset> = listOf(
            Free,
            Original,
            Square,
            FourByThree,
            ThreeByFour,
            SixteenByNine,
            NineBySixteen,
        )
    }
}

/**
 * Configuration for [ImageEditorScreen]. All fields have sensible defaults so callers
 * can pass [Default] to get a working editor.
 *
 * Kept theme-free on purpose so the module is reusable in any Compose project; the
 * editor renders inside the host's [androidx.compose.material3.MaterialTheme].
 */
@Immutable
data class ImageEditorConfig(
    val aspectRatios: List<AspectRatioPreset> = AspectRatioPreset.Defaults,
    val drawingPalette: List<Color> = DefaultPalette,
    val strokeWidthsDp: List<Float> = DefaultStrokeWidthsDp,
    val outputQuality: Int = 92,
    val outputFormat: OutputFormat = OutputFormat.JPEG,
) {
    enum class OutputFormat { JPEG, PNG }

    companion object {
        val Default = ImageEditorConfig()

        val DefaultPalette: List<Color> = listOf(
            Color.White,
            Color.Black,
            Color(0xFFE53935), // red
            Color(0xFFFB8C00), // orange
            Color(0xFFFDD835), // yellow
            Color(0xFF43A047), // green
            Color(0xFF1E88E5), // blue
            Color(0xFF8E24AA), // purple
        )

        val DefaultStrokeWidthsDp: List<Float> = listOf(3f, 6f, 12f, 20f)
    }
}
