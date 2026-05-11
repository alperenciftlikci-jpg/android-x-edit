/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.text.style.TextAlign
import io.element.android.libraries.core.perf.BitmapDecoders
import io.element.android.libraries.core.perf.trace
import io.element.android.libraries.core.perf.traceAsync
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
 * 3. Rasterize drawing paths and text items on top of the rotated bitmap. Path
 *    coordinates are normalized against the on-screen canvas, which has the same
 *    aspect as the rotated bitmap, so positions match what the user saw.
 * 4. Crop to [EditorState.cropRect] (also in normalized rotated-image space).
 * 5. Encode (JPEG/PNG) and write to cache.
 *
 * Heavy work runs on [Dispatchers.IO]. The caller is responsible for deleting the
 * returned file when done with it.
 */
object BitmapExporter {

    // Reusable Paints + Typeface — kept at object level so we don't reallocate them across
    // sequential exports. State (color, strokeWidth, textSize…) is set per draw call before use.
    // Concurrent exports are not expected (the UI gates the Apply button while one is in flight),
    // so we don't need ThreadLocal here. If that invariant ever changes, switch to per-call locals.
    private val sharedStrokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sharedTextPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val sharedBackgroundPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    suspend fun export(
        context: Context,
        sourceUri: Uri,
        state: EditorState,
        densityScale: Float,
        fontScale: Float = context.resources.configuration.fontScale,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        traceAsync("imageeditor.export") {
            runCatching {
                val source = trace("imageeditor.export.decode") {
                    loadBitmap(context, sourceUri)
                        ?: error("Could not decode image at $sourceUri")
                }

                // 1. Rotate + flip — bitmap now matches what the user is looking at.
                val transformed = trace("imageeditor.export.transform") {
                    applyRotateAndFlip(source, state.rotationDegrees, state.flippedHorizontally)
                }
                if (transformed !== source) source.recycle()

                // 2. Annotate (draw + text) on the rotated bitmap. Path/text coords are
                //    normalized to the on-screen canvas, which we sized to the rotated
                //    image's aspect ratio — so they translate 1:1 here. We pass the
                //    canvas pixel size so stroke widths in dp scale correctly.
                val canvasW = state.canvasSizePx.width.takeIf { it > 0 } ?: transformed.width
                val annotated = if (state.drawnPaths.isNotEmpty() || state.textItems.isNotEmpty()) {
                    val mutable = transformed.copy(Bitmap.Config.ARGB_8888, true)
                    if (mutable !== transformed) transformed.recycle()
                    val canvas = Canvas(mutable)
                    if (state.drawnPaths.isNotEmpty()) {
                        trace("imageeditor.export.draw.paths") {
                            drawPaths(canvas, state.drawnPaths, mutable.width.toFloat(), mutable.height.toFloat(), canvasW, densityScale)
                        }
                    }
                    if (state.textItems.isNotEmpty()) {
                        trace("imageeditor.export.draw.text") {
                            drawTexts(canvas, state.textItems, mutable.width.toFloat(), mutable.height.toFloat(), canvasW, densityScale, fontScale)
                        }
                    }
                    mutable
                } else {
                    transformed
                }

                // 3. Crop applied last — overlays drawn outside the crop rect get clipped
                //    away, which matches the user's expectation when they crop after
                //    annotating.
                val cropped = trace("imageeditor.export.crop") {
                    applyCrop(annotated, state.cropRect)
                }
                if (cropped !== annotated) annotated.recycle()

                // 4. Encode.
                trace("imageeditor.export.encode") {
                    val outFile = newCacheFile(context, state.config.outputFormat)
                    FileOutputStream(outFile).use { out ->
                        val format = when (state.config.outputFormat) {
                            ImageEditorConfig.OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                            ImageEditorConfig.OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                        }
                        cropped.compress(format, state.config.outputQuality, out)
                        out.flush()
                    }
                    cropped.recycle()
                    Uri.fromFile(outFile)
                }
            }
        }
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? =
        BitmapDecoders.decodeFullRes(context, uri)

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

    private fun drawPaths(
        canvas: Canvas,
        paths: List<DrawingPath>,
        w: Float,
        h: Float,
        canvasPxWidth: Int,
        densityScale: Float,
    ) {
        // The user's canvas was [canvasPxWidth] pixels wide and contained an image
        // with the same aspect as this bitmap. To make a 6dp pen on screen render
        // at the same visual thickness in the exported bitmap, scale by the ratio
        // bitmap_w / canvas_w. No floor — for small images the scale must shrink,
        // otherwise the canvas-space size leaks into the bitmap and overflows it.
        val strokeScale = if (canvasPxWidth > 0) {
            w / canvasPxWidth.toFloat()
        } else {
            w / 1080f
        }
        val strokePaint = sharedStrokePaint
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
    }

    private fun drawTexts(
        canvas: Canvas,
        texts: List<TextItem>,
        w: Float,
        h: Float,
        canvasPxWidth: Int,
        densityScale: Float,
        fontScale: Float,
    ) {
        // Text — sp size on the editor canvas needs to translate to bitmap pixels.
        // We treat the editor canvas as having displayed the bitmap at fit-width on a
        // ~1080px-wide phone, so multiply font size by (bitmap_width / 1080) for parity.
        val strokeScale = if (canvasPxWidth > 0) w / canvasPxWidth.toFloat() else w / 1080f
        val textPaint = sharedTextPaint
        val backgroundPaint = sharedBackgroundPaint
        // Match TextOverlay.kt — `padding(horizontal = 8dp, vertical = 4dp)`.
        val padX = 8f * densityScale * strokeScale
        val padY = 4f * densityScale * strokeScale
        val cornerR = 6f * densityScale * strokeScale

        for (item in texts) {
            if (item.text.isBlank()) continue
            textPaint.color = item.color.toArgbColor()
            // Match Compose's BasicText fontSize.sp render: density × fontScale × sp size.
            // Without fontScale, an editor whose system font scale is 1.2× would draw text 20%
            // bigger on canvas than in the exported bitmap, making positions look mis-aligned.
            val sizePx = item.fontSizeSp * densityScale * fontScale * strokeScale
            textPaint.textSize = sizePx
            // Paint.textAlign is irrelevant for StaticLayout — alignment is set on the layout.

            // Two-pass StaticLayout to match Compose `BasicText` alignment behavior.
            //
            // Why two passes: StaticLayout's `width` parameter doubles as the alignment frame —
            // ALIGN_CENTER centres text inside *that* width, not inside the text's own bounds.
            // Passing the remaining bitmap width (e.g. `bitmap_w - blockLeft - 2*padX`) means the
            // text gets centred over the right half of the bitmap, dragging the visible glyphs
            // ~0.3*w to the right of where the user placed the text on canvas. Compose BasicText
            // does the right thing: the widget shrinks to the max line width and *then* applies
            // alignment within that. We replicate that here:
            //   Pass 1: build with the wrap-bound width to discover the natural line widths.
            //   Pass 2: rebuild with `maxLineWidth` as the layout width, applying alignment
            //           within the text's own bounds. Per-line ALIGN_CENTER offsets are now
            //           identical to Compose's.
            val blockLeft = item.position.x * w
            val blockTop = item.position.y * h
            val wrapBoundWidth = (w - blockLeft - 2f * padX).coerceAtLeast(1f).toInt()
            val alignment = when (item.align) {
                TextAlign.Left, TextAlign.Start -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.Right, TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_CENTER
            }
            // Pass 1 — discover the actual line widths under the wrap constraint.
            val measureLayout = StaticLayout.Builder
                .obtain(item.text, 0, item.text.length, textPaint, wrapBoundWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            if (measureLayout.lineCount == 0) continue
            var maxLineWidth = 0f
            for (i in 0 until measureLayout.lineCount) {
                val lw = measureLayout.getLineWidth(i)
                if (lw > maxLineWidth) maxLineWidth = lw
            }
            // Pass 2 — final layout sized to the actual content; alignment now applies inside
            // the text's own bounds, matching Compose.
            val finalLayoutWidth = maxLineWidth.toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder
                .obtain(item.text, 0, item.text.length, textPaint, finalLayoutWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            val blockW = maxLineWidth + 2f * padX
            val blockH = layout.height.toFloat() + 2f * padY

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

            // StaticLayout draws its lines with their own internal x positions according to
            // `alignment`. We translate the canvas to the (left,top) inside the padded box and
            // then call `layout.draw`. `save/restore` keeps subsequent text items unaffected.
            val saveCount = canvas.save()
            canvas.translate(blockLeft + padX, blockTop + padY)
            layout.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
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
