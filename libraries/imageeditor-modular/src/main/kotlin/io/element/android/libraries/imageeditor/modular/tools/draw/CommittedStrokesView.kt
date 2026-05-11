/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.modular.tools.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/**
 * Holds the **committed** strokes layer for the modular drawing tool. Lives outside the Compose
 * tree (mounted via `AndroidView`) so the per-frame cost of redrawing N strokes is isolated from
 * Compose recomposition and from `InProgressStrokes`' SurfaceView z-order changes — both of which
 * were forcing the Compose `Canvas` / `drawWithCache` approach to re-rasterise at input rate,
 * dragging the stroke gesture down to ~16 fps even with a cache.
 *
 * Strategy:
 *  1. We rasterise all committed strokes once into an offscreen `Bitmap` using
 *     [CanvasStrokeRenderer] (same renderer Jetpack Ink itself uses) on a software canvas.
 *  2. `onDraw` does a single hardware `drawBitmap` blit of that cached bitmap. O(1) per frame.
 *  3. The cache is invalidated only when the strokes list reference changes or the view is
 *     resized — neither of which happens during a live drawing gesture, so the live overlay can
 *     submit frames freely without touching us.
 *
 * The [setStrokes] entry point checks the list reference to skip the no-op case where Compose
 * passes the same list back to us across recompositions.
 */
internal class CommittedStrokesView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create()
    private val identity = Matrix()
    private var strokes: List<Stroke> = emptyList()
    private var cachedBitmap: Bitmap? = null

    /**
     * Number of strokes we have already painted into [cachedBitmap]. The list passed to
     * [setStrokes] is normally an append-only mutation (user finishes a stroke), so we can keep
     * the existing bitmap and just paint the *new* tail of the list. Falls back to a full
     * rebuild on undo (list shrunk) or any other mismatch.
     */
    private var paintedStrokeCount: Int = 0

    /**
     * Pending action for [onDraw] to perform on the cache before blitting. We don't rasterise
     * eagerly inside [setStrokes] because Compose can call us multiple times per frame; deferring
     * to [onDraw] means we coalesce into a single update.
     */
    private enum class PendingCacheOp { None, IncrementalAppend, FullRebuild }
    private var pending: PendingCacheOp = PendingCacheOp.None

    init {
        // We never animate ourselves; the parent invalidates us when strokes change.
        setWillNotDraw(false)
    }

    fun setStrokes(newStrokes: List<Stroke>) {
        // Identity-equality short-circuit — Compose hands us the same `inkStrokes` SnapshotStateList
        // reference across recompositions, so a structural compare would be a wasted O(N).
        if (newStrokes === strokes && newStrokes.size == paintedStrokeCount) return
        val incremental = isAppendOnly(strokes, newStrokes)
        strokes = newStrokes
        pending = if (incremental) {
            // Promote a pending FullRebuild over an append — a rebuild already covers everything.
            if (pending == PendingCacheOp.FullRebuild) PendingCacheOp.FullRebuild
            else PendingCacheOp.IncrementalAppend
        } else {
            PendingCacheOp.FullRebuild
        }
        invalidate()
    }

    /**
     * True iff [next] starts with all of [previous] (same elements, same order) and only adds
     * trailing strokes. The fast path — appending a finished stroke — should hit this. Any
     * shrinking (undo) or reordering returns false and forces a full rebuild.
     */
    private fun isAppendOnly(previous: List<Stroke>, next: List<Stroke>): Boolean {
        if (next.size < previous.size) return false
        for (i in previous.indices) {
            if (previous[i] !== next[i]) return false
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Bitmap allocations are tied to size; drop the cache and force a rebuild on next draw.
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

    /**
     * Fast path: paint only the strokes added since the last cache update. O(newStrokes) instead
     * of O(allStrokes). Hit on every regular "user finished a stroke" mutation.
     */
    private fun appendNewStrokesToCache() {
        val target = cachedBitmap ?: run {
            // No cache yet — fall through to full rebuild.
            rebuildCacheFromScratch()
            return
        }
        val c = Canvas(target)
        for (i in paintedStrokeCount until strokes.size) {
            renderer.draw(stroke = strokes[i], canvas = c, strokeToScreenTransform = identity)
        }
        paintedStrokeCount = strokes.size
    }

    /**
     * Slow path: clear the bitmap and re-rasterise everything. Used on undo, on resize, and on
     * the first paint when no cache exists.
     */
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
