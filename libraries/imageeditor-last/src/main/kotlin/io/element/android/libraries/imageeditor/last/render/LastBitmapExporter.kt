/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last.render

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
import io.element.android.libraries.imageeditor.last.LastEditorState
import io.element.android.libraries.imageeditor.last.LastImageEditorConfig
import io.element.android.libraries.imageeditor.last.LastTextBackgroundMode
import io.element.android.libraries.imageeditor.last.LastTextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Hybrid bitmap exporter. Pipeline:
 *
 *   sourceUri (or uCrop pre-baked Uri) → decode.full → transform (rotate/flip)*
 *     → draw.strokes (Ink stroke geometry → Path/Paint, since CanvasStrokeRenderer no-ops on
 *                     software canvas)
 *     → draw.text   (StaticLayout, two-pass for correct alignment within text bounds)
 *     → crop*       (only if no uCrop hand-off happened)
 *     → encode      (Bitmap.compress → cache file)
 *
 * `*` skipped when the caller already produced a transformed-and-cropped bitmap via uCrop's
 * `cropAndSaveImage`; in that case we just paint overlays + encode.
 *
 * Stroke width: `brush.size × sx`. The state holder set `Brush.size = dp × density` already,
 * so on the editor canvas the stroke renders at the user-picked dp width. The `sx` factor
 * (canvas-px → bitmap-px) scales it proportionally to the bitmap.
 *
 * Text size: `fontSize × density × fontScale × strokeScale` to match Compose `BasicText`'s
 * fontSize.sp render path (which honours system font scale).
 *
 * Text alignment: two-pass StaticLayout. Pass 1 measures actual line widths under the wrap
 * constraint; pass 2 builds the final layout sized to `maxLineWidth` so `ALIGN_CENTER` /
 * `ALIGN_OPPOSITE` apply within the text's own bounds (matching Compose) instead of within the
 * remaining bitmap-right alley.
 */
object LastBitmapExporter {

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
        state: LastEditorState,
        densityScale: Float,
        skipTransformAndCrop: Boolean = false,
        fontScale: Float = context.resources.configuration.fontScale,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        traceAsync("imageeditor.last.export") {
            runCatching {
                val source = trace("imageeditor.last.export.decode") {
                    BitmapDecoders.decodeFullRes(context, sourceUri)
                        ?: error("Could not decode image at $sourceUri")
                }

                val transformed = if (skipTransformAndCrop) {
                    source
                } else {
                    val t = trace("imageeditor.last.export.transform") {
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
                        trace("imageeditor.last.export.draw.strokes") {
                            drawInkStrokes(canvas, state.inkStrokes, sx, sy)
                        }
                    }
                    if (state.textItems.isNotEmpty()) {
                        trace("imageeditor.last.export.draw.text") {
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
                    val c = trace("imageeditor.last.export.crop") {
                        applyCrop(annotated, state.cropLeft, state.cropTop, state.cropRight, state.cropBottom)
                    }
                    if (c !== annotated) annotated.recycle()
                    c
                }

                trace("imageeditor.last.export.encode") {
                    val outFile = newCacheFile(context, state.config.outputFormat)
                    FileOutputStream(outFile).use { out ->
                        val format = when (state.config.outputFormat) {
                            LastImageEditorConfig.OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                            LastImageEditorConfig.OutputFormat.PNG -> Bitmap.CompressFormat.PNG
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

    private fun drawInkStrokes(canvas: Canvas, strokes: List<Stroke>, sx: Float, sy: Float) {
        val paint = sharedStrokePaint
        val tmp = StrokeInput()
        for (stroke in strokes) {
            val inputs = stroke.inputs
            val n = inputs.size
            if (n < 2) continue
            paint.color = stroke.brush.colorIntArgb
            // brush.size is in screen pixels (state holder already applied dp × density).
            // Multiply by sx (canvas-px → bitmap-px) only.
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
        texts: List<LastTextItem>,
        w: Float,
        h: Float,
        canvasPxWidth: Int,
        densityScale: Float,
        fontScale: Float,
    ) {
        val textPaint = sharedTextPaint
        val backgroundPaint = sharedBackgroundPaint

        val strokeScale = if (canvasPxWidth > 0) w / canvasPxWidth.toFloat() else w / 1080f
        val padX = 8f * densityScale * strokeScale
        val padY = 4f * densityScale * strokeScale
        val cornerR = 6f * densityScale * strokeScale

        for (item in texts) {
            if (item.text.isBlank()) continue
            textPaint.color = item.color.toArgbColor()
            // Compose BasicText fontSize.sp = density × fontScale × sp. Mirror that here.
            val sizePx = item.fontSizeSp * densityScale * fontScale * strokeScale
            textPaint.textSize = sizePx

            val blockLeft = item.position.x * w
            val blockTop = item.position.y * h
            val wrapBoundWidth = (w - blockLeft - 2f * padX).coerceAtLeast(1f).toInt()
            val alignment = when (item.align) {
                TextAlign.Left, TextAlign.Start -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.Right, TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_CENTER
            }
            // Pass 1 — measure actual line widths under the wrap constraint.
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
            // Pass 2 — final layout sized to actual content; alignment applies within own bounds.
            val finalLayoutWidth = maxLineWidth.toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder
                .obtain(item.text, 0, item.text.length, textPaint, finalLayoutWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            val blockW = maxLineWidth + 2f * padX
            val blockH = layout.height.toFloat() + 2f * padY

            val bgArgb = when (item.backgroundMode) {
                LastTextBackgroundMode.None -> 0
                LastTextBackgroundMode.Solid -> item.backgroundColor.toArgbColor()
                LastTextBackgroundMode.SemiTransparent -> {
                    val raw = item.backgroundColor.toArgbColor()
                    (0x80 shl 24) or (raw and 0x00ffffff)
                }
            }
            if (item.backgroundMode != LastTextBackgroundMode.None) {
                backgroundPaint.color = bgArgb
                canvas.drawRoundRect(
                    blockLeft, blockTop, blockLeft + blockW, blockTop + blockH,
                    cornerR, cornerR, backgroundPaint,
                )
            }

            val saveCount = canvas.save()
            canvas.translate(blockLeft + padX, blockTop + padY)
            layout.draw(canvas)
            canvas.restoreToCount(saveCount)
        }
    }

    private fun newCacheFile(context: Context, format: LastImageEditorConfig.OutputFormat): File {
        val dir = File(context.cacheDir, "image-editor-last").apply { mkdirs() }
        val ext = when (format) {
            LastImageEditorConfig.OutputFormat.JPEG -> "jpg"
            LastImageEditorConfig.OutputFormat.PNG -> "png"
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
