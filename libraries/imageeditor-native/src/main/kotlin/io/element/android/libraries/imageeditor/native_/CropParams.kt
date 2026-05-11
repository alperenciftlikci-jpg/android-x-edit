/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_

/**
 * Crop / rotate / flip transform.
 *
 *  - `x`, `y`, `w`, `h`: crop rect in normalised image-space [0, 1]
 *  - `rotation90`: 0..3 quarter-turns CW
 *  - `freeAngle`: free rotation in degrees, [-45, +45], applied *before* the 90° step
 *  - `mirrorH` / `mirrorV`: pre-rotate flips
 *
 * Default value is the identity (full image, no rotation, no flip) — exporter skips
 * the crop pass entirely in that case.
 */
data class CropParams(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
    val rotation90: Int = 0,
    val freeAngle: Float = 0f,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        x, y, w, h,
        rotation90.toFloat(),
        freeAngle,
        if (mirrorH) 1f else 0f,
        if (mirrorV) 1f else 0f,
    )
}
