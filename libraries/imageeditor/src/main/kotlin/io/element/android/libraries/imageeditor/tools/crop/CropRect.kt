/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.tools.crop

import androidx.compose.runtime.Immutable

/**
 * Crop rectangle in normalized image coordinates (0f..1f). When [Full] every value is
 * 0/0/1/1 which means "no crop applied".
 */
@Immutable
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)
    }
}
