/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.text

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/** How the background block is drawn behind a [TextItem]. */
enum class TextBackgroundMode {
    /** No background — only the glyphs are drawn. */
    None,

    /** Opaque rectangle in [TextItem.backgroundColor] behind the text. */
    Solid,

    /** Semi-transparent (~50%) rectangle in [TextItem.backgroundColor] behind the text. */
    SemiTransparent,
}

/**
 * A single text annotation. [position] is normalized to (0f..1f) of the image
 * canvas so it survives transforms; [fontSizeSp] is interpreted as scale-independent
 * pixels relative to the rendered canvas height.
 */
@Immutable
data class TextItem(
    val id: Long,
    val text: String,
    val color: Color,
    val fontSizeSp: Float,
    val position: Offset,
    val align: TextAlign = TextAlign.Center,
    val backgroundMode: TextBackgroundMode = TextBackgroundMode.None,
    val backgroundColor: Color = Color.Black,
)
