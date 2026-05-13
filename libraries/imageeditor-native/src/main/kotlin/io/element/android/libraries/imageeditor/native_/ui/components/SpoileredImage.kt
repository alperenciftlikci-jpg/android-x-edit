/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Image + Telegram-style spoiler overlay, sized as a single unit.
 *
 * Visual recipe (mirrors `ChatMessageCell.drawBlurredPhoto` in Telegram-Android — see the
 * subagent notes in the conversation that diffed our implementation against theirs):
 *
 *  1. **Downscale the source bitmap to ~20 dp wide.** Telegram literally throws away
 *     resolution: 20 dp is roughly 60–80 px on a modern phone, much smaller than the
 *     displayed thumbnail. Bilinear upscaling back to the display rect is what produces
 *     the smooth "frosted-glass" backdrop. Replacing `Modifier.blur(60dp)` with this
 *     bitmap trick is the single biggest visual fix — render-effect blur doesn't smush
 *     high-contrast features (sun emoji, faces) anywhere near as hard as a 20 dp resample
 *     does.
 *  2. **Apply a Telegram-style ColorMatrix:** saturation × 1.6, brightness × 0.9 (a.k.a.
 *     `getFancyBlurFilter()` in `ChatMessageCell.java:28420`). The saturation boost is
 *     what makes the backdrop feel "painterly" rather than washed-out, and it kills the
 *     need for the dark scrim the earlier version of this composable used.
 *  3. **Letterbox identically to the original.** Wrapping both children in an inner Box
 *     with `Modifier.aspectRatio(bitmap.aspect)` reproduces the rect a regular
 *     `ContentScale.Fit` Image would land in, so the dust never paints outside the photo
 *     bounds.
 *
 * @param revealable forwarded to [SpoilerOverlay]. `true` for chat-bubble usage (recipient
 *                   can tap to reveal), `false` for editor / pre-send preview where the
 *                   author is authoring rather than consuming.
 */
@Composable
fun SpoileredImage(
    bitmap: ImageBitmap,
    isSpoiler: Boolean,
    modifier: Modifier = Modifier,
    revealable: Boolean = false,
    onRevealed: () -> Unit = {},
    contentDescription: String? = null,
) {
    val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
    val backdrop = if (isSpoiler) rememberSpoilerBackdrop(bitmap) else bitmap
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.aspectRatio(aspect),
            contentAlignment = Alignment.Center,
        ) {
            // Display the pre-blurred backdrop bitmap. NO `Modifier.blur` on top —
            // the bitmap is already stack-blurred at pixel level by
            // rememberSpoilerBackdrop (mirrors Telegram's `Utilities.stackBlurBitmapMax`
            // + `getFancyBlurFilter` ColorMatrix). Using Modifier.blur in addition would
            // re-introduce the RenderNode-shader edge-bleed that made black photos look
            // grey/white in the user's first test.
            Image(
                bitmap = backdrop,
                contentDescription = contentDescription,
                contentScale = if (isSpoiler) ContentScale.FillBounds else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (isSpoiler) {
                SpoilerOverlay(
                    modifier = Modifier.fillMaxSize(),
                    revealable = revealable,
                    onRevealed = onRevealed,
                )
            }
        }
    }
}

/**
 * Produce Telegram's "fancy blur" backdrop bitmap. Cached against the source bitmap so a
 * recompose-induced rebuild doesn't redo the work; recomputed whenever the source changes.
 *
 * The dimensions match `Utilities.stackBlurBitmapMax` in Telegram-Android: width pinned to
 * 20 dp, height proportional, never below 8 px on either axis (guards against degenerate
 * source bitmaps). Bilinear filter (`Paint.isFilterBitmap = true`) is what turns the
 * downscale into a smooth blur on the upscale.
 */
@Composable
private fun rememberSpoilerBackdrop(source: ImageBitmap): ImageBitmap {
    val density = LocalDensity.current
    return remember(source) {
        val src: Bitmap = source.asAndroidBitmap()
        if (src.width <= 0 || src.height <= 0) return@remember source

        val targetW = with(density) { 20.dp.toPx() }.toInt().coerceAtLeast(8)
        val aspect = src.height.toFloat() / src.width.toFloat()
        val targetH = (targetW * aspect).toInt().coerceAtLeast(8)

        // Telegram's `getFancyBlurFilter()` order (ChatMessageCell.java:28420):
        //   1) multiplyBrightnessColorMatrix(.9f)   — postConcat the dim matrix first
        //   2) adjustSaturationColorMatrix(+0.6f)   — then postConcat the saturation
        // postConcat applies on the LEFT mathematically, so the composed matrix is
        // `sat * dim` and per-pixel the dim is applied first (dim, then sat).
        // For a uniform-RGB dim like 0.9·I the matrices commute, so the visible result
        // matches even if we built it the other way — we preserve Telegram's order here
        // for readability and to keep the path easy to extend if the ratio diverges
        // (e.g. a non-uniform brightness curve).
        val matrix = ColorMatrix()
        matrix.postConcat(ColorMatrix(floatArrayOf(
            0.9f, 0f, 0f, 0f, 0f,
            0f, 0.9f, 0f, 0f, 0f,
            0f, 0f, 0.9f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        matrix.postConcat(ColorMatrix().apply { setSaturation(1.6f) })

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        val dst = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        // Opaque black underlay so transparent source pixels don't bleed through to
        // the parent background. Stack-blur then smooths the boundary between the
        // source's opaque content and the black surround.
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, targetW, targetH),
            paint,
        )
        // Telegram-faithful stack-blur on the already-downscaled bitmap. Radius is the
        // same constant Telegram ships (`Math.max(10, max(w, h) / 150)` — for a 20-dp-
        // wide bitmap the second term is ≤ 1, so the radius collapses to 10). Operating
        // on the bitmap pixels at this stage produces a uniformly opaque dst that the
        // display path can render with no shader-blur, no background-bleed, no
        // alpha-edge artefacts.
        StackBlur.blur(dst, radius = 10)
        dst.asImageBitmap()
    }
}
