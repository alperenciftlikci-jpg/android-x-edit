/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/**
 * Position is stored in normalised image-space (0..1 each axis), top-left of the padded text
 * block. `BitmapExporter` translates to bitmap pixels via `pos × bitmap_w/h`.
 */
data class LastTextItem(
    val id: Long = 0L,
    val text: String = "",
    val position: Offset = Offset(0.4f, 0.45f),
    val color: Color = Color.White,
    val backgroundColor: Color = Color.Black,
    val backgroundMode: LastTextBackgroundMode = LastTextBackgroundMode.None,
    val fontSizeSp: Float = 36f,
    val align: TextAlign = TextAlign.Center,
)

enum class LastTextBackgroundMode { None, Solid, SemiTransparent }
