/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Coil transformation that produces Telegram's "fancy blurred photo" — a tiny stack-blurred
 * downscale of the source with the saturation+brightness ColorMatrix applied. AsyncImage in
 * the spoilered chat bubble loads with this transformation; on reveal we drop it and Coil
 * fetches the untransformed bitmap (already in the disk/memory cache for the same URL).
 *
 * Why a Coil transformation instead of `Modifier.blur` on the loaded photo: Modifier.blur
 * is a RenderNode shader applied at composition time — it modifies how the photo is
 * rendered, not the underlying bitmap. The user reports the photo "looking different"
 * before vs after revealing the spoiler, because Modifier.blur + Modifier.background
 * changes the rendered output of the SAME bitmap. Telegram avoids this by keeping two
 * separate `ImageReceiver`s: one for the original (`photoImage`) and one for the
 * pre-blurred copy (`blurredPhotoImage`). They draw whichever one matches the spoiler
 * state. We mirror that here: pre-blurred bitmap during spoiler, plain bitmap after
 * reveal. The displayed `AsyncImage` carries no extra render-time modifiers, so the
 * photo's appearance is byte-for-byte preserved when the spoiler comes off.
 *
 * Algorithm exactly matches `SpoileredImage.rememberSpoilerBackdrop`:
 *   1. Downscale to 20-px-wide intermediate (matches Telegram's `dp(20)`).
 *   2. Apply ColorMatrixColorFilter — `setSaturation(1.6f)` + brightness × 0.9.
 *   3. Stack-blur radius 10 (via native [SpoilerJni] when libphotoedit.so is loaded,
 *      Kotlin [StackBlur] fallback otherwise).
 */
class SpoilerCoilTransformation : Transformation() {

    override val cacheKey: String = "spoiler-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (input.width <= 0 || input.height <= 0) return input

        // Width pinned to 60 px (3× 20 dp at typical phone density). Coil's `size`
        // tells us the request's target dimensions, but for the spoiler we always
        // want the tiny intermediate — the final upscale happens at draw time.
        val targetW = 60
        val aspect = input.height.toFloat() / input.width.toFloat()
        val targetH = (targetW * aspect).toInt().coerceAtLeast(8)

        val matrix = ColorMatrix()
        matrix.postConcat(
            ColorMatrix(floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 0.9f, 0f, 0f, 0f,
                0f, 0f, 0.9f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
        )
        matrix.postConcat(ColorMatrix().apply { setSaturation(1.6f) })

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        val dst = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        // Opaque black underlay — transparent source edges blend to black instead of
        // bleeding to a parent layer's bg colour (cf. the user-reported white halo
        // bug when we relied on Modifier.background+blur).
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            input,
            Rect(0, 0, input.width, input.height),
            Rect(0, 0, targetW, targetH),
            paint,
        )
        StackBlur.blur(dst, radius = 10)
        return dst
    }
}
