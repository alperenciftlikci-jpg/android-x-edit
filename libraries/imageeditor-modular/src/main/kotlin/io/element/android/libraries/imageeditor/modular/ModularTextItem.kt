/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.imageeditor.modular

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/**
 * Same shape as the baseline `TextItem` so the modular editor can re-use the same overlay UI
 * conceptually. Position is stored in normalised image-space (0..1 each axis).
 */
data class ModularTextItem(
    val id: Long = 0L,
    val text: String = "",
    val position: Offset = Offset(0.5f, 0.5f),
    val color: Color = Color.White,
    val backgroundColor: Color = Color.Black,
    val backgroundMode: TextBackgroundMode = TextBackgroundMode.None,
    val fontSizeSp: Float = 28f,
    val align: TextAlign = TextAlign.Center,
)

enum class TextBackgroundMode { None, Solid, SemiTransparent }
