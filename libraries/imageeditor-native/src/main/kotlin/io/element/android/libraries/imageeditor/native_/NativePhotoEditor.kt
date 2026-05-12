/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_

import android.graphics.Bitmap
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Kotlin wrapper around the native `libphotoedit.so`.
 *
 * Threading model: every native call is dispatched onto a single dedicated GL thread so the
 * EGL context stays bound to one thread for the lifetime of the editor. Kotlin callers can
 * call this from any thread (Main, IO, etc.) — the wrapper blocks until the GL thread
 * finishes the operation and returns the result. EGL contexts are TLS-bound and can't be
 * shared across `Dispatchers.IO`'s 64-thread pool, which was previously causing every
 * setSource / export to silently fail with EGL_BAD_ACCESS.
 *
 * Lifecycle: [close] tears down the GL context + textures + FBOs. The dedicated thread is
 * shut down at the same time. Use `AutoCloseable` (`use { }`) for one-shot exports.
 */
class NativePhotoEditor : AutoCloseable {
    private val glExecutor = Executors.newSingleThreadExecutor(GlThreadFactory)
    private var handle: Long = 0L

    init {
        handle = onGl { nativeCreate() }
        if (handle == 0L) {
            Timber.e("NativePhotoEditor: nativeCreate returned 0 — native side init failed")
        } else {
            Timber.d("NativePhotoEditor: native handle=0x%x", handle)
        }
    }

    val isReady: Boolean get() = handle != 0L

    /**
     * Upload [source] as the editor's input texture. We always copy to ARGB_8888 software-backed
     * before handing the bitmap off to JNI — `BitmapFactory.decodeStream` and friends can hand us
     * back HARDWARE bitmaps (no CPU pixels) or RGB_565, both of which fail `AndroidBitmap_lockPixels`
     * silently inside `BitmapHelper`. The defensive copy lives here so the call site doesn't have
     * to know.
     */
    fun setSource(source: Bitmap): Boolean {
        if (handle == 0L) return false
        val safe = ensureSoftwareArgb8888(source)
        val ok = onGl { nativeSetSourceBitmap(handle, safe) }
        if (!ok) {
            Timber.e("nativeSetSourceBitmap returned false (size=%dx%d, config=%s)",
                safe.width, safe.height, safe.config)
        } else {
            Timber.d("setSource ok: %dx%d", safe.width, safe.height)
        }
        // If we materialised a copy, recycle it — JNI side already memcpy'd into the GL texture.
        if (safe !== source) safe.recycle()
        return ok
    }

    private fun ensureSoftwareArgb8888(src: Bitmap): Bitmap {
        val isHardware = android.os.Build.VERSION.SDK_INT >= 26 &&
                src.config == Bitmap.Config.HARDWARE
        return if (!isHardware && src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, /* mutable = */ false)
    }

    /**
     * Render the current pipeline into [destination] (must be RGBA_8888 + mutable). Output size
     * is taken from the destination bitmap — pass a smaller bitmap to downscale, larger to
     * upscale. Returns false if the native side wasn't ready or the bitmap couldn't be locked.
     */
    fun exportTo(destination: Bitmap): Boolean {
        if (handle == 0L) return false
        if (destination.config != Bitmap.Config.ARGB_8888) {
            Timber.w("exportTo: destination bitmap is not ARGB_8888 (was %s)", destination.config)
            return false
        }
        if (!destination.isMutable) {
            Timber.w("exportTo: destination bitmap is immutable")
            return false
        }
        val ok = onGl { nativeExportToBitmap(handle, destination) }
        if (!ok) Timber.e("nativeExportToBitmap returned false (dst=%dx%d)",
            destination.width, destination.height)
        return ok
    }

    /**
     * Update the filter pipeline parameters. Cheap to call repeatedly — the native side
     * just stores the struct; actual GPU work happens at next [exportTo] call. Identity-
     * value filters are skipped automatically by the chain so a default `FilterParams()`
     * costs zero passes.
     */
    fun setFilterParams(params: FilterParams) {
        if (handle == 0L) return
        onGl {
            nativeSetFilterParams(
                handle,
                params.toFloatArray(),
                params.curves.data,
                params.curves.isIdentity,
            )
        }
    }

    /** Update the crop / rotate / flip transform. Identity skips the crop pass. */
    fun setCropParams(params: CropParams) {
        if (handle == 0L) return
        onGl { nativeSetCropParams(handle, params.toFloatArray()) }
    }

    /** One-shot render path. Atomically updates filter + crop params, then exports into the
     *  destination bitmap, all on the dedicated GL thread. Previously the preview loop
     *  invoked four separate `onGl` blocks (setFilterParams / setCropParams /
     *  croppedOutputSize / exportTo), each round-tripping through `submit()/get()` and
     *  contributing its own queue overhead. Folding the sequence cuts queue churn by 4×
     *  and keeps the state mutation + render atomic, so a tab switch can never observe
     *  a half-applied state. */
    fun renderPreviewInto(
        dst: Bitmap,
        filterParams: FilterParams,
        cropParams: CropParams,
    ): Boolean {
        if (handle == 0L) return false
        if (dst.config != Bitmap.Config.ARGB_8888 || !dst.isMutable) return false
        return onGl {
            nativeSetFilterParams(
                handle,
                filterParams.toFloatArray(),
                filterParams.curves.data,
                filterParams.curves.isIdentity,
            )
            nativeSetCropParams(handle, cropParams.toFloatArray())
            nativeExportToBitmap(handle, dst)
        }
    }

    /**
     * Returns `(width, height)` of the bitmap that would result from running the current
     * pipeline (filters + crop). Use this to allocate the destination bitmap before
     * [exportTo] when you want pixel-accurate output (rather than a fixed preview size).
     */
    fun croppedOutputSize(): Pair<Int, Int> {
        if (handle == 0L) return 0 to 0
        val arr = onGl { nativeCroppedOutputSize(handle) }
        return arr[0] to arr[1]
    }

    /**
     * Begin a paint stroke at `(x, y)` (paint-layer pixel coords; same coordinate space as the
     * source bitmap) with the given brush. Subsequent samples should go through [extendStroke].
     * Pressure is normalised in `[0, 1]` and multiplies the brush radius — pass `1f` for finger
     * input.
     */
    fun beginStroke(brush: Brush, x: Float, y: Float, pressure: Float = 1f) {
        if (handle == 0L) return
        onGl { nativeBeginStroke(handle, brush.toFloatArray(), x, y, pressure) }
    }

    fun extendStroke(x: Float, y: Float, pressure: Float = 1f) {
        if (handle == 0L) return
        onGl { nativeExtendStroke(handle, x, y, pressure) }
    }

    fun endStroke() {
        if (handle == 0L) return
        onGl { nativeEndStroke(handle) }
    }

    /** Roll back the most recent paint stroke (Pen / Marker / Neon / Arrow / Eraser).
     *  Blur-brush strokes live on their own stack — see [undoBlur]. */
    fun undoPaint() {
        if (handle == 0L) return
        onGl { nativeUndoPaint(handle) }
    }

    /** Re-apply the most recently undone paint stroke. No-op if a new stroke has been started since. */
    fun redoPaint() {
        if (handle == 0L) return
        onGl { nativeRedoPaint(handle) }
    }

    /** Roll back the most recent blur-brush stroke. Independent of paint undo so the user
     *  can erase a blur smudge without losing their paint marks. */
    fun undoBlur() {
        if (handle == 0L) return
        onGl { nativeUndoBlur(handle) }
    }

    fun redoBlur() {
        if (handle == 0L) return
        onGl { nativeRedoBlur(handle) }
    }

    /** Wipe the entire paint layer + history. Cannot be undone. */
    fun clearPaint() {
        if (handle == 0L) return
        onGl { nativeClearPaint(handle) }
    }

    /**
     * Add or replace a text item. The bitmap should be rendered Kotlin-side via `StaticLayout`
     * (or `Canvas.drawText`) at the desired font size, with **premultiplied alpha** RGBA_8888.
     * Position is in paint-layer pixel coords (the source bitmap's coordinate space). Scale +
     * rotation are applied around the bitmap's centre. Identity transform: scale=1, rotation=0.
     *
     * To re-position an existing item without re-rendering the bitmap, pass the exact same
     * bitmap dimensions — the native side detects the no-resize case and skips the texture
     * upload, keeping per-frame overhead negligible.
     */
    fun upsertTextItem(
        id: Int,
        bitmap: Bitmap,
        x: Float,
        y: Float,
        scale: Float = 1f,
        rotationRad: Float = 0f,
    ): Boolean {
        if (handle == 0L) return false
        if (bitmap.config != Bitmap.Config.ARGB_8888) return false
        return onGl { nativeUpsertTextItem(handle, id, bitmap, x, y, scale, rotationRad) }
    }

    fun removeTextItem(id: Int) {
        if (handle == 0L) return
        onGl { nativeRemoveTextItem(handle, id) }
    }

    fun clearText() {
        if (handle == 0L) return
        onGl { nativeClearText(handle) }
    }

    override fun close() {
        if (handle != 0L) {
            // Run release on the GL thread so EGL teardown sees its own context bound.
            try {
                onGl { nativeRelease(handle) }
            } catch (t: Throwable) {
                Timber.e(t, "nativeRelease threw")
            }
            handle = 0L
        }
        glExecutor.shutdown()
    }

    /** Submit work to the dedicated GL thread and block until it completes. The result type is
     *  whatever the lambda returns. Throws if the lambda throws. */
    private fun <T> onGl(block: () -> T): T = glExecutor.submit(block).get()

    private external fun nativeCreate(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeSetSourceBitmap(handle: Long, bitmap: Bitmap): Boolean
    private external fun nativeExportToBitmap(handle: Long, dst: Bitmap): Boolean
    private external fun nativeHello(): String
    private external fun nativeSetFilterParams(
        handle: Long,
        scalars: FloatArray,
        curveLut: FloatArray,
        curveIsIdentity: Boolean,
    )
    private external fun nativeSetCropParams(handle: Long, packed: FloatArray)
    private external fun nativeCroppedOutputSize(handle: Long): IntArray
    private external fun nativeBeginStroke(handle: Long, brush: FloatArray, x: Float, y: Float, pressure: Float)
    private external fun nativeExtendStroke(handle: Long, x: Float, y: Float, pressure: Float)
    private external fun nativeEndStroke(handle: Long)
    private external fun nativeUndoPaint(handle: Long)
    private external fun nativeRedoPaint(handle: Long)
    private external fun nativeUndoBlur(handle: Long)
    private external fun nativeRedoBlur(handle: Long)
    private external fun nativeClearPaint(handle: Long)
    private external fun nativeUpsertTextItem(
        handle: Long, id: Int, bitmap: Bitmap,
        x: Float, y: Float, scale: Float, rotationRad: Float,
    ): Boolean
    private external fun nativeRemoveTextItem(handle: Long, id: Int)
    private external fun nativeClearText(handle: Long)

    private object GlThreadFactory : ThreadFactory {
        private var counter = 0
        override fun newThread(r: Runnable): Thread =
            Thread(r, "photoedit-gl-${counter++}").apply { isDaemon = true }
    }

    companion object {
        init {
            try {
                System.loadLibrary("photoedit")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load libphotoedit.so")
                throw t
            }
        }

        /** Sanity-check accessor — returns the native version string. Useful for asserting the
         *  library actually loaded before instantiating an editor. */
        fun probe(): String = NativePhotoEditor().use { editor ->
            editor.onGl { editor.nativeHello() }
        }
    }
}
