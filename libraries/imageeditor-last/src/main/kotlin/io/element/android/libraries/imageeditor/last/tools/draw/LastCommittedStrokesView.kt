/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last.tools.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/**
 * Custom View that hosts the **committed strokes** layer for the hybrid editor.
 *
 * Lives outside the Compose tree (mounted via `AndroidView`) so the per-frame cost of redrawing
 * N strokes is isolated from Compose recomposition and from `InProgressStrokes`' SurfaceView
 * z-order changes — both of which were forcing the Compose `Canvas` / `drawWithCache` approach
 * to re-rasterise at input rate, dragging the stroke gesture FPS to single digits even with a
 * cache.
 *
 * Strategy:
 *  1. Rasterise committed strokes into an offscreen ARGB_8888 `Bitmap` using
 *     [CanvasStrokeRenderer].
 *  2. `onDraw` does a single hardware `drawBitmap` blit. O(1) per frame.
 *  3. Cache invalidation is **append-only**: when [setStrokes] receives a list whose elements
 *     are an extension of the previous list (the common case — user finished a stroke), only
 *     the new tail is painted onto the existing bitmap. Undo (list shrunk) or any reordering
 *     forces a full rebuild.
 */
internal class LastCommittedStrokesView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create()
    private val identity = Matrix()
    private var strokes: List<Stroke> = emptyList()
    private var cachedBitmap: Bitmap? = null
    private var paintedStrokeCount: Int = 0

    private enum class PendingCacheOp { None, IncrementalAppend, FullRebuild }
    private var pending: PendingCacheOp = PendingCacheOp.None

    init {
        setWillNotDraw(false)
    }

    fun setStrokes(newStrokes: List<Stroke>) {
        if (newStrokes === strokes && newStrokes.size == paintedStrokeCount) return
        val incremental = isAppendOnly(strokes, newStrokes)
        strokes = newStrokes
        pending = if (incremental) {
            if (pending == PendingCacheOp.FullRebuild) PendingCacheOp.FullRebuild
            else PendingCacheOp.IncrementalAppend
        } else {
            PendingCacheOp.FullRebuild
        }
        invalidate()
    }

    private fun isAppendOnly(previous: List<Stroke>, next: List<Stroke>): Boolean {
        if (next.size < previous.size) return false
        for (i in previous.indices) {
            if (previous[i] !== next[i]) return false
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedBitmap?.recycle()
        cachedBitmap = null
        paintedStrokeCount = 0
        pending = PendingCacheOp.FullRebuild
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cachedBitmap?.recycle()
        cachedBitmap = null
        paintedStrokeCount = 0
    }

    override fun onDraw(canvas: Canvas) {
        when (pending) {
            PendingCacheOp.IncrementalAppend -> appendNewStrokesToCache()
            PendingCacheOp.FullRebuild -> rebuildCacheFromScratch()
            PendingCacheOp.None -> Unit
        }
        pending = PendingCacheOp.None
        cachedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun appendNewStrokesToCache() {
        val target = cachedBitmap ?: run {
            rebuildCacheFromScratch()
            return
        }
        val c = Canvas(target)
        for (i in paintedStrokeCount until strokes.size) {
            renderer.draw(stroke = strokes[i], canvas = c, strokeToScreenTransform = identity)
        }
        paintedStrokeCount = strokes.size
    }

    private fun rebuildCacheFromScratch() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (strokes.isEmpty()) {
            cachedBitmap?.eraseColor(0)
            paintedStrokeCount = 0
            return
        }
        val target = cachedBitmap ?: Bitmap
            .createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .also { cachedBitmap = it }
        val c = Canvas(target)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        for (stroke in strokes) {
            renderer.draw(stroke = stroke, canvas = c, strokeToScreenTransform = identity)
        }
        paintedStrokeCount = strokes.size
    }
}
