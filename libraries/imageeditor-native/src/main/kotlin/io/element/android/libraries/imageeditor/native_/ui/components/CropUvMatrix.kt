/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import io.element.android.libraries.imageeditor.native_.CropParams
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kotlin mirror of `CropEngine::buildUvMatrix` in `CropEngine.cpp`. Given the same
 * `CropParams`, produces an identical 2D affine that maps OUTPUT uv coords (post-crop,
 * post-rotation, in `[0, 1]`) back to SOURCE uv coords (pre-crop, also in `[0, 1]`).
 *
 * Why this lives Kotlin-side: paint touch coordinates and text-entity positions both need
 * to know "which source pixel does this on-screen position correspond to?", and the answer
 * depends on the current crop / rotation / mirror state. Re-implementing the GL shader's
 * math here keeps editor preview (touch → source pixel) and the eventual native render
 * (source pixel → output via the same matrix in the shader) perfectly aligned under any
 * rotation / mirror / crop combination.
 *
 * Forward direction is identical to the shader's `uv_src = M · vec3(uv, 1)`:
 *   ```
 *   srcUv.x = m[0] * ox + m[1] * oy + m[4]
 *   srcUv.y = m[2] * ox + m[3] * oy + m[5]
 *   ```
 */
internal object CropUvMatrix {

    /** Flat 6-element array `[a, b, c, d, tx, ty]` representing `srcUv = M · outputUv`. */
    fun build(p: CropParams): FloatArray {
        var a = 1f; var b = 0f
        var c = 0f; var d = 1f
        var tx = -0.5f; var ty = -0.5f

        // Pre-rotate mirror (matches native `M_mirror` step).
        if (p.mirrorH) { a = -a; c = -c; tx = -tx }
        if (p.mirrorV) { b = -b; d = -d; ty = -ty }

        // Free-angle rotation (counter-clockwise in degrees).
        val ang = p.freeAngle * Math.PI.toFloat() / 180f
        val ca = cos(ang)
        val sa = sin(ang)
        val a2 = ca * a - sa * b
        val b2 = sa * a + ca * b
        val c2 = ca * c - sa * d
        val d2 = sa * c + ca * d
        val tx2 = ca * tx - sa * ty
        val ty2 = sa * tx + ca * ty
        a = a2; b = b2; c = c2; d = d2; tx = tx2; ty = ty2

        // 90° rotation step — applied `q` times, where each step is (x, y) → (y, -x).
        val q = ((p.rotation90 % 4) + 4) % 4
        for (i in 0 until q) {
            val ax = c; val bx = d; val cx = -a; val dx = -b
            val txq = ty; val tyq = -tx
            a = ax; b = bx; c = cx; d = dx; tx = txq; ty = tyq
        }

        // Translate back from centred (-0.5 origin) to standard (0, 1) uv origin.
        tx += 0.5f
        ty += 0.5f

        // Crop rect: scale by (w, h) and offset by (x, y) in source uv.
        a *= p.w; b *= p.h
        c *= p.w; d *= p.h
        tx = tx * p.w + p.x
        ty = ty * p.h + p.y

        return floatArrayOf(a, b, c, d, tx, ty)
    }

    /** `srcUv = M · (ox, oy, 1)` — the forward direction used by the GL shader. */
    fun apply(m: FloatArray, ox: Float, oy: Float): Pair<Float, Float> =
        (m[0] * ox + m[1] * oy + m[4]) to (m[2] * ox + m[3] * oy + m[5])

    /** Output bitmap dimensions after `cropParams` applied to a `srcW × srcH` source.
     *  Matches the body of `CropEngine::outputSize`, minus the `inscribedScale` shrink
     *  that triggers only when `|freeAngle| > 1e-3` (we skip that for editor-side
     *  letterbox math — small enough effect to ignore unless the user actually uses
     *  free-angle, in which case touches near the inscribed boundary may drift by a few
     *  pixels, which is acceptable). */
    fun outputDims(srcW: Int, srcH: Int, p: CropParams): Pair<Int, Int> {
        val cw = (p.w * srcW).toInt().coerceAtLeast(1)
        val ch = (p.h * srcH).toInt().coerceAtLeast(1)
        return if (p.rotation90 % 2 != 0) ch to cw else cw to ch
    }
}
