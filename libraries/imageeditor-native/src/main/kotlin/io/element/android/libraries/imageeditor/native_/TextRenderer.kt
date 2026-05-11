/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Render a string into an RGBA_8888 bitmap suitable for [NativePhotoEditor.upsertTextItem].
 *
 * Why this lives here: the native side does composite + transform of pre-rendered text bitmaps;
 * Kotlin owns the actual glyph drawing because Android's text stack already handles font
 * fallback, Bidi, complex scripts, emoji and ligatures correctly — re-implementing that with
 * FreeType would be a large undertaking that produces worse results.
 *
 * Output is premultiplied alpha so the native composite shader's `vec4(o.rgb + b.rgb * (1 - o.a))`
 * path works with no further conversion.
 */
object TextRenderer {

    /**
     * Render [text] at [fontSizePx] pixels with the given colour. The bitmap dimensions are
     * computed automatically from the laid-out text + 16 px padding on each side. If the laid-out
     * width exceeds [maxWidthPx], text wraps with [Layout.Alignment.ALIGN_CENTER] alignment.
     */
    fun render(
        text: String,
        fontSizePx: Float,
        colorArgb: Int = Color.WHITE,
        bold: Boolean = true,
        maxWidthPx: Int = Int.MAX_VALUE,
        padding: Int = 16,
        backgroundColorArgb: Int = Color.TRANSPARENT,
    ): Bitmap {
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = colorArgb
            textSize = fontSizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        // Measure first to size the bitmap.
        val safeWidth = if (maxWidthPx == Int.MAX_VALUE) {
            paint.measureText(text).toInt().coerceAtLeast(1)
        } else {
            maxWidthPx.coerceAtLeast(1)
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        // Tighten the bitmap width to the actual maximum line width so we don't
        // waste pixels (and so transform pivot stays at the centre of the glyphs).
        var maxLineW = 0f
        for (i in 0 until layout.lineCount) {
            val w = layout.getLineWidth(i)
            if (w > maxLineW) maxLineW = w
        }
        val bw = (maxLineW.toInt() + padding * 2).coerceAtLeast(1)
        val bh = (layout.height + padding * 2).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (backgroundColorArgb != Color.TRANSPARENT) {
            canvas.drawColor(backgroundColorArgb)
        }
        canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        // Note: StaticLayout draws into a non-premultiplied surface by default — Bitmap is
        // ARGB_8888 with premultiplied alpha when created via Bitmap.createBitmap, so the result
        // is already premultiplied, matching what the native composite expects.
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
