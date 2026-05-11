/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.modular.render

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
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import io.element.android.libraries.core.perf.BitmapDecoders
import io.element.android.libraries.core.perf.trace
import io.element.android.libraries.core.perf.traceAsync
import io.element.android.libraries.imageeditor.modular.ModularEditorState
import io.element.android.libraries.imageeditor.modular.ModularImageEditorConfig
import io.element.android.libraries.imageeditor.modular.ModularTextItem
import io.element.android.libraries.imageeditor.modular.TextBackgroundMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Modular variant of `BitmapExporter`. Same pipeline shape (decode → transform → draw → crop →
 * encode), but the draw step uses [CanvasStrokeRenderer] from Jetpack Ink instead of our hand-
 * rolled `Path`/`Paint` painting.
 *
 * Trace section names are `imageeditor.modular.export.*` so they line up alphabetically with the
 * baseline exporter on the dashboard for direct comparison.
 */
object ModularBitmapExporter {

    // Reusable Paints — set state per draw call. See [BitmapExporter] for the same hoisting
    // rationale; concurrent exports are not expected because the UI gates Apply.
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

    /**
     * Run the export pipeline.
     *
     * @param skipTransformAndCrop set to true when [sourceUri] is already a fully
     *   transformed+cropped bitmap (e.g. uCrop just wrote its result there). In that
     *   case we only need to paint overlays and encode.
     */
    suspend fun export(
        context: Context,
        sourceUri: Uri,
        state: ModularEditorState,
        densityScale: Float,
        skipTransformAndCrop: Boolean = false,
        fontScale: Float = context.resources.configuration.fontScale,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        traceAsync("imageeditor.modular.export") {
            runCatching {
                val source = trace("imageeditor.modular.export.decode") {
                    loadBitmap(context, sourceUri)
                        ?: error("Could not decode image at $sourceUri")
                }

                val transformed = if (skipTransformAndCrop) {
                    source
                } else {
                    val t = trace("imageeditor.modular.export.transform") {
                        applyRotateAndFlip(source, state.rotationDegrees, state.flippedHorizontally)
                    }
                    if (t !== source) source.recycle()
                    t
                }

                val canvasW = state.canvasSizePx.width.takeIf { it > 0 } ?: transformed.width
                val canvasH = state.canvasSizePx.height.takeIf { it > 0 } ?: transformed.height
                val annotated = if (state.inkStrokes.isNotEmpty() || state.textItems.isNotEmpty()) {
                    val mutable = transformed.copy(Bitmap.Config.ARGB_8888, true)
                    if (mutable !== transformed) transformed.recycle()
                    val canvas = Canvas(mutable)
                    val w = mutable.width.toFloat()
                    val h = mutable.height.toFloat()
                    val sx = if (canvasW > 0) w / canvasW.toFloat() else 1f
                    val sy = if (canvasH > 0) h / canvasH.toFloat() else sx
                    if (state.inkStrokes.isNotEmpty()) {
                        trace("imageeditor.modular.export.draw.strokes") {
                            drawInkStrokes(canvas, state.inkStrokes, sx, sy, densityScale)
                        }
                    }
                    if (state.textItems.isNotEmpty()) {
                        trace("imageeditor.modular.export.draw.text") {
                            drawTexts(canvas, state.textItems, w, h, canvasW, densityScale, fontScale)
                        }
                    }
                    mutable
                } else {
                    transformed
                }

                val cropped = if (skipTransformAndCrop) {
                    annotated
                } else {
                    val c = trace("imageeditor.modular.export.crop") {
                        applyCrop(annotated, state.cropLeft, state.cropTop, state.cropRight, state.cropBottom)
                    }
                    if (c !== annotated) annotated.recycle()
                    c
                }

                trace("imageeditor.modular.export.encode") {
                    val outFile = newCacheFile(context, state.config.outputFormat)
                    FileOutputStream(outFile).use { out ->
                        val format = when (state.config.outputFormat) {
                            ModularImageEditorConfig.OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                            ModularImageEditorConfig.OutputFormat.PNG -> Bitmap.CompressFormat.PNG
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

    private fun applyCrop(source: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap {
        if (l == 0f && t == 0f && r == 1f && b == 1f) return source
        val left = (l * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (t * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (r * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (b * source.height).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /**
     * Render committed Ink strokes as `Path`/`Paint`. We don't use [androidx.ink.rendering.android.canvas.CanvasStrokeRenderer]
     * here because it relies on hardware-accelerated rendering and silently no-ops on the software
     * canvas we get from a freshly-allocated Bitmap. Path/Paint is what the baseline editor already
     * uses, so this also keeps the export-render comparison apples-to-apples.
     *
     * Stroke geometry is reconstructed from `stroke.inputs` (the recorded touch samples) which
     * gives us a polyline. Brush colour and width come from `stroke.brush`.
     */
    private fun drawInkStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        sx: Float,
        sy: Float,
        @Suppress("UNUSED_PARAMETER") densityScale: Float,
    ) {
        val paint = sharedStrokePaint
        val tmp = StrokeInput()
        for (stroke in strokes) {
            val inputs = stroke.inputs
            val n = inputs.size
            if (n < 2) continue
            paint.color = stroke.brush.colorIntArgb
            // Stroke width: stroke.brush.size is in *screen pixels* — that's how Jetpack Ink's
            // CanvasStrokeRenderer interprets it on the editor canvas (renderer is called with
            // an identity matrix in CommittedStrokesView / InProgressStrokes, so brush.size px
            // == stroke px on screen). To translate that to bitmap pixels we only multiply by
            // the canvas→bitmap ratio (`sx`). Multiplying by `densityScale` on top — which the
            // previous version did — was double-counting density and made exported strokes
            // ~`density`× thicker than they appeared in the editor.
            paint.strokeWidth = stroke.brush.size * sx
            val path = Path()
            for (i in 0 until n) {
                inputs.populate(i, tmp)
                val x = tmp.x * sx
                val y = tmp.y * sy
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawTexts(
        canvas: Canvas,
        texts: List<ModularTextItem>,
        w: Float,
        h: Float,
        canvasPxWidth: Int,
        densityScale: Float,
        fontScale: Float,
    ) {
        val textPaint = sharedTextPaint
        val backgroundPaint = sharedBackgroundPaint

        // Same scale convention as the baseline exporter so text reads identically across editors.
        val strokeScale = if (canvasPxWidth > 0) w / canvasPxWidth.toFloat() else w / 1080f
        val padX = 8f * densityScale * strokeScale
        val padY = 4f * densityScale * strokeScale
        val cornerR = 6f * densityScale * strokeScale

        for (item in texts) {
            if (item.text.isBlank()) continue
            textPaint.color = item.color.toArgbColor()
            // Match Compose BasicText: density × fontScale. See BitmapExporter for rationale.
            val sizePx = item.fontSizeSp * densityScale * fontScale * strokeScale
            textPaint.textSize = sizePx

            // Two-pass StaticLayout to match Compose BasicText alignment — see BitmapExporter.kt
            // for the full rationale (ALIGN_CENTER otherwise centres text within the entire
            // remaining-right-half of the bitmap, not within the text's own bounds, dragging
            // the visible glyphs ~0.3*w right of the user-placed position).
            val blockLeft = item.position.x * w
            val blockTop = item.position.y * h
            val wrapBoundWidth = (w - blockLeft - 2f * padX).coerceAtLeast(1f).toInt()
            val alignment = when (item.align) {
                TextAlign.Left, TextAlign.Start -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.Right, TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_CENTER
            }
            // Pass 1 — measure natural line widths under wrap constraint.
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
            // Pass 2 — final layout sized to actual content for correct alignment.
            val finalLayoutWidth = maxLineWidth.toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder
                .obtain(item.text, 0, item.text.length, textPaint, finalLayoutWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            val blockW = maxLineWidth + 2f * padX
            val blockH = layout.height.toFloat() + 2f * padY

            val bgArgb = when (item.backgroundMode) {
                TextBackgroundMode.None -> 0
                TextBackgroundMode.Solid -> item.backgroundColor.toArgbColor()
                TextBackgroundMode.SemiTransparent -> {
                    val raw = item.backgroundColor.toArgbColor()
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

            val saveCount = canvas.save()
            canvas.translate(blockLeft + padX, blockTop + padY)
            layout.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }

    private fun newCacheFile(context: Context, format: ModularImageEditorConfig.OutputFormat): File {
        val dir = File(context.cacheDir, "image-editor-modular").apply { mkdirs() }
        val ext = when (format) {
            ModularImageEditorConfig.OutputFormat.JPEG -> "jpg"
            ModularImageEditorConfig.OutputFormat.PNG -> "png"
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
