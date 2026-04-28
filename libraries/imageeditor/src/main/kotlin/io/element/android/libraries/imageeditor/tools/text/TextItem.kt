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
)
