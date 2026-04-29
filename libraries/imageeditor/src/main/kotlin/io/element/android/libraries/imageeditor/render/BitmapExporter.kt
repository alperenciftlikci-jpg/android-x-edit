/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.style.TextAlign
import io.element.android.libraries.imageeditor.ImageEditorConfig
import io.element.android.libraries.imageeditor.tools.text.TextBackgroundMode
import io.element.android.libraries.imageeditor.state.EditorState
import io.element.android.libraries.imageeditor.tools.crop.CropRect
import io.element.android.libraries.imageeditor.tools.draw.DrawingPath
import io.element.android.libraries.imageeditor.tools.text.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Flattens all edits in [state] into a single bitmap, writes it to the host's cache
 * directory, and returns the resulting [Uri].
 *
 * Pipeline:
 * 1. Load source bitmap from [sourceUri].
 * 2. Apply rotation + horizontal flip via Matrix.
 * 3. Crop to [EditorState.cropRect] in image-space coordinates.
 * 4. Rasterize drawing paths and text items on top.
 * 5. Encode (JPEG/PNG) and write to cache.
 *
 * Heavy work runs on [Dispatchers.IO]. The caller is responsible for deleting the
 * returned file when done with it.
 */
object BitmapExporter {

    suspend fun export(
        context: Context,
        sourceUri: Uri,
        state: EditorState,
        densityScale: Float,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val source = loadBitmap(context, sourceUri)
                ?: error("Could not decode image at $sourceUri")

            // 1. Rotate + flip.
            val transformed = applyRotateAndFlip(source, state.rotationDegrees, state.flippedHorizontally)
            if (transformed !== source) source.recycle()

            // 2. Crop.
            val cropped = applyCrop(transformed, state.cropRect)
            if (cropped !== transformed) transformed.recycle()

            // 3. Annotate (draw + text). Mutable copy required.
            val annotated = if (state.drawnPaths.isNotEmpty() || state.textItems.isNotEmpty()) {
                val mutable = cropped.copy(Bitmap.Config.ARGB_8888, true)
                if (mutable !== cropped) cropped.recycle()
                drawAnnotations(mutable, state.drawnPaths, state.textItems, densityScale)
                mutable
            } else {
                cropped
            }

            // 4. Encode.
            val outFile = newCacheFile(context, state.config.outputFormat)
            FileOutputStream(outFile).use { out ->
                val format = when (state.config.outputFormat) {
                    ImageEditorConfig.OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageEditorConfig.OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                }
                annotated.compress(format, state.config.outputQuality, out)
                out.flush()
            }
            annotated.recycle()
            Uri.fromFile(outFile)
        }
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    private fun applyRotateAndFlip(source: Bitmap, rotationDegrees: Int, flipH: Boolean): Bitmap {
        if (rotationDegrees == 0 && !flipH) return source
        val matrix = Matrix().apply {
            if (flipH) postScale(-1f, 1f, source.width / 2f, source.height / 2f)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyCrop(source: Bitmap, cropRect: CropRect): Bitmap {
        if (cropRect.isFull) return source
        val left = (cropRect.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (cropRect.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (cropRect.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (cropRect.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun drawAnnotations(
        bitmap: Bitmap,
        paths: List<DrawingPath>,
        texts: List<TextItem>,
        densityScale: Float,
    ) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Drawings — line strokes encoded in normalized coordinates.
        // Choose a bitmap-relative stroke factor so a 6dp pen on a phone screen stays
        // proportionally similar after export to a high-resolution bitmap.
        val strokeScale = (w / 1080f).coerceAtLeast(1f)

        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (path in paths) {
            if (path.points.size < 2) continue
            strokePaint.color = path.color.toArgbColor()
            strokePaint.strokeWidth = path.strokeWidthDp * densityScale * strokeScale
            val nativePath = Path().apply {
                val first = path.points.first()
                moveTo(first.x * w, first.y * h)
                for (i in 1 until path.points.size) {
                    val p = path.points[i]
                    lineTo(p.x * w, p.y * h)
                }
            }
            canvas.drawPath(nativePath, strokePaint)
        }

        // Text — sp size on the editor canvas needs to translate to bitmap pixels.
        // We treat the editor canvas as having displayed the bitmap at fit-width on a
        // ~1080px-wide phone, so multiply font size by (bitmap_width / 1080) for parity.
        val textPaint = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val backgroundPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        // Match TextOverlay.kt — `padding(horizontal = 8dp, vertical = 4dp)`.
        val padX = 8f * densityScale * strokeScale
        val padY = 4f * densityScale * strokeScale
        val cornerR = 6f * densityScale * strokeScale

        for (item in texts) {
            if (item.text.isBlank()) continue
            textPaint.color = item.color.toArgbColor()
            val sizePx = item.fontSizeSp * densityScale * strokeScale
            textPaint.textSize = sizePx

            // Wrap to fit within the bitmap width if needed.
            val blockLeft = item.position.x * w
            val blockTop = item.position.y * h
            val maxWidth = (w - blockLeft - 2f * padX).coerceAtLeast(1f)
            val lines = wrapText(item.text, textPaint, maxWidth)
            if (lines.isEmpty()) continue

            val lineWidths = lines.map { textPaint.measureText(it) }
            val maxLineW = lineWidths.maxOrNull() ?: 0f
            val lineHeight = textPaint.fontMetrics.run { descent - ascent + leading }
            val blockW = maxLineW + 2f * padX
            val blockH = lines.size * lineHeight + 2f * padY

            // Background block.
            val bgArgb = when (item.backgroundMode) {
                TextBackgroundMode.None -> 0
                TextBackgroundMode.Solid -> item.backgroundColor.toArgbColor()
                TextBackgroundMode.SemiTransparent -> {
                    val raw = item.backgroundColor.toArgbColor()
                    // Replace alpha with ~50%.
                    (0x80 shl 24) or (raw and 0x00ffffff)
                }
            }
            if (item.backgroundMode != TextBackgroundMode.None) {
                backgroundPaint.color = bgArgb
                canvas.drawRoundRect(
                    blockLeft,
                    blockTop,
                    blockLeft + blockW,
                    blockTop + blockH,
                    cornerR,
                    cornerR,
                    backgroundPaint,
                )
            }

            // Pick Paint anchor + reference x to match the requested alignment.
            val (anchor, refX) = when (item.align) {
                TextAlign.Left, TextAlign.Start -> Paint.Align.LEFT to (blockLeft + padX)
                TextAlign.Right, TextAlign.End -> Paint.Align.RIGHT to (blockLeft + blockW - padX)
                else -> Paint.Align.CENTER to (blockLeft + blockW / 2f)
            }
            textPaint.textAlign = anchor

            var y = blockTop + padY - textPaint.fontMetrics.ascent
            for (line in lines) {
                canvas.drawText(line, refX, y, textPaint)
                y += lineHeight
            }
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return text.lines()
        val result = mutableListOf<String>()
        for (rawLine in text.lines()) {
            if (paint.measureText(rawLine) <= maxWidth) {
                result += rawLine
                continue
            }
            val words = rawLine.split(' ')
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current.clear().append(candidate)
                } else {
                    if (current.isNotEmpty()) result += current.toString()
                    current.clear().append(word)
                }
            }
            if (current.isNotEmpty()) result += current.toString()
        }
        return result
    }

    private fun newCacheFile(context: Context, format: ImageEditorConfig.OutputFormat): File {
        val dir = File(context.cacheDir, "image-editor").apply { mkdirs() }
        val ext = when (format) {
            ImageEditorConfig.OutputFormat.JPEG -> "jpg"
            ImageEditorConfig.OutputFormat.PNG -> "png"
        }
        return File(dir, "edited_${System.currentTimeMillis()}.$ext")
    }

    private fun androidx.compose.ui.graphics.Color.toArgbColor(): Int {
        val r = (red * 255f).toInt() and 0xff
        val g = (green * 255f).toInt() and 0xff
        val b = (blue * 255f).toInt() and 0xff
        val a = (alpha * 255f).toInt() and 0xff
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
