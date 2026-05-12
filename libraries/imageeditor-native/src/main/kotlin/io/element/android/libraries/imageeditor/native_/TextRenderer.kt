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
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Visual frame variants matching the Compose-side `TextFrameType`. Kept here as well so the
 *  rendering pipeline can bake the frame straight into the text bitmap at export time —
 *  without this, the editor preview (Compose-drawn frame) and the JPEG (no frame) drift. */
enum class TextFrame { Plain, Solid, Semi, Outline }

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
        frame: TextFrame = TextFrame.Plain,
    ): Bitmap {
        // Frame colours follow the same rule as the Compose-side TextEntityBody:
        //   Solid:    bg = swatch,        text = white/black chosen by swatch brightness
        //   Semi:     bg = swatch @ 60 %, text = white/black chosen by swatch brightness
        //   Outline:  bg = transparent,   text = swatch, frame stroke = swatch
        //   Plain:    bg = transparent,   text = swatch
        val swatchR = ((colorArgb shr 16) and 0xFF) / 255f
        val swatchG = ((colorArgb shr 8) and 0xFF) / 255f
        val swatchB = (colorArgb and 0xFF) / 255f
        val brightness = swatchR * 0.299f + swatchG * 0.587f + swatchB * 0.114f
        val (bgColor, textColor) = when (frame) {
            TextFrame.Solid -> {
                val bg = colorArgb or 0xFF000000.toInt() // force opaque
                val tx = if (brightness >= 0.721f) Color.BLACK else Color.WHITE
                bg to tx
            }
            TextFrame.Semi -> {
                val bg = (colorArgb and 0x00FFFFFF) or 0x99000000.toInt() // ~60% alpha
                val tx = if (brightness >= 0.5f) Color.BLACK else Color.WHITE
                bg to tx
            }
            TextFrame.Outline, TextFrame.Plain -> Color.TRANSPARENT to colorArgb
        }

        val paint = TextPaint().apply {
            isAntiAlias = true
            color = textColor
            textSize = fontSizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
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

        var maxLineW = 0f
        for (i in 0 until layout.lineCount) {
            val w = layout.getLineWidth(i)
            if (w > maxLineW) maxLineW = w
        }

        // Extra padding for filled / outline frames so the box has visible margin around
        // the glyphs — matches the Compose-side `padding(horizontal = 10.dp, vertical = 4.dp)`
        // converted to fontSize-relative pixels (~20% horizontal, ~10% vertical of glyph).
        val framePadX = if (frame == TextFrame.Plain) 0 else (fontSizePx * 0.20f).toInt()
        val framePadY = if (frame == TextFrame.Plain) 0 else (fontSizePx * 0.10f).toInt()
        val totalPadX = padding + framePadX
        val totalPadY = padding + framePadY

        val bw = (maxLineW.toInt() + totalPadX * 2).coerceAtLeast(1)
        val bh = (layout.height + totalPadY * 2).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1) Draw frame backdrop (only for Solid / Semi / Outline).
        if (frame == TextFrame.Solid || frame == TextFrame.Semi) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
            val cornerRadius = fontSizePx * 0.12f
            val rect = RectF(
                padding.toFloat(),
                padding.toFloat(),
                (bw - padding).toFloat(),
                (bh - padding).toFloat(),
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        } else if (frame == TextFrame.Outline) {
            val strokeWidth = fontSizePx * 0.04f
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorArgb
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            val cornerRadius = fontSizePx * 0.12f
            val inset = strokeWidth / 2f
            val rect = RectF(
                padding.toFloat() + inset,
                padding.toFloat() + inset,
                (bw - padding).toFloat() - inset,
                (bh - padding).toFloat() - inset,
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        }

        // 2) Draw the glyphs.
        canvas.save()
        canvas.translate(totalPadX.toFloat(), totalPadY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
}
