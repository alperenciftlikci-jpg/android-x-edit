/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.core.perf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Centralized bitmap decoding helpers used by the image-editor variants.
 *
 * The two big wins this enforces:
 *
 *  1. **Bounds-only decode** before any pixel allocation — needed when we only want the image's
 *     intrinsic dimensions (e.g. to size a Compose `Box` aspect ratio). A 12 MP photo at
 *     ARGB_8888 is 48 MB; doing a full decode just to read width/height is wasteful and on
 *     low-RAM devices it can OOM the process before the editor opens.
 *
 *  2. **Right-size decode** for the on-screen preview. A modern phone camera produces 4032×3024
 *     images, but the device screen is ~1080–1440 px wide; loading the full-res bitmap into the
 *     preview wastes ~8x the memory it needs to. `decodeForDisplay` uses `inSampleSize` to
 *     downsample at decode time so the bitmap that lands in the heap is already close to the
 *     pixels we'll actually paint.
 *
 * Sections fed to [trace]/[traceAsync]:
 *  - `imageeditor.decode.bounds` — bounds-only pass.
 *  - `imageeditor.decode.display` — downsampled preview decode.
 *  - `imageeditor.decode.full`   — full-resolution decode for export.
 *
 * Same prefix as the editor sections so the dashboard groups them visually.
 */
object BitmapDecoders {
    /**
     * Decode just the image header to read its width × height. Returns `null` if the URI cannot
     * be opened or the codec rejects the stream.
     */
    fun decodeBoundsOnly(context: Context, uri: Uri): IntPair? = trace("imageeditor.decode.bounds") {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            IntPair(opts.outWidth, opts.outHeight)
        } else {
            null
        }
    }

    /**
     * Decode at the smallest power-of-two scale that still covers a [maxWidth] × [maxHeight] box.
     * Use this for **on-screen preview** bitmaps; export should use [decodeFullRes].
     *
     * `inSampleSize` only takes powers of two — picking a value larger than what we strictly need
     * just means the next power-of-two boundary downsizes more aggressively. We round *up* (=
     * smaller bitmap) deliberately: for a 4032-wide source and a 1080-wide target, sampleSize=4
     * gives 1008 px which is close enough to fill the 1080-wide stage.
     *
     * @param config defaults to ARGB_8888 (full quality). Pass [Bitmap.Config.RGB_565] for opaque
     *   sources where alpha isn't needed — halves the heap footprint.
     */
    fun decodeForDisplay(
        context: Context,
        uri: Uri,
        maxWidth: Int,
        maxHeight: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? = trace("imageeditor.decode.display") {
        // First pass: bounds only.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@trace null

        // Second pass: downsample. Pick the largest sampleSize that still keeps both dimensions
        // ≥ the requested max box. Power-of-two only.
        var sampleSize = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxWidth && h / 2 >= maxHeight) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }

        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = config
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decode)
        }
    }

    /**
     * Decode at native resolution. Use only when the bitmap will be flattened and saved (export
     * path). For preview, use [decodeForDisplay].
     */
    fun decodeFullRes(context: Context, uri: Uri): Bitmap? = trace("imageeditor.decode.full") {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    /** Lightweight Int-pair so this util has no Compose / androidx dependency. */
    data class IntPair(val width: Int, val height: Int)
}
