/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.draw

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * One stroke drawn by the user. Points are stored in **image-space** normalized
 * coordinates (0f..1f on each axis) so they survive zoom / rotate / aspect changes
 * and can be reproduced when flattening to the final bitmap.
 */
@Immutable
data class DrawingPath(
    val points: List<Offset>,
    val color: Color,
    val strokeWidthDp: Float,
)
