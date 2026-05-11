/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_

import android.graphics.Bitmap
import timber.log.Timber

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val fps: Float,
    val rotation: Int,
    val hasAudio: Boolean,
) {
    val isEmpty get() = width <= 0 || height <= 0
}

/**
 * Wraps the native [VideoDecoder] (FFmpeg-backed). One instance = one open file.
 *
 * Typical use:
 * ```
 * NativeVideoDecoder().use { dec ->
 *     dec.open("/path/file.mp4")
 *     val md = dec.metadata
 *     val bm = Bitmap.createBitmap(md.width, md.height, Bitmap.Config.ARGB_8888)
 *     while (true) {
 *         when (dec.decodeNextFrame(bm)) {
 *             DecodeResult.Frame -> /* use bm */
 *             DecodeResult.EndOfStream, DecodeResult.Error -> break
 *         }
 *     }
 * }
 * ```
 *
 * Thread-safety: not thread-safe. Use from a single decode thread (e.g. the
 * pipeline thread); the underlying FFmpeg state machine assumes serial access.
 */
class NativeVideoDecoder : AutoCloseable {
    private var handle: Long = nativeCreate()
    private val ptsScratch = LongArray(1)

    val isOpen: Boolean get() = handle != 0L && metadataInternal != null
    private var metadataInternal: VideoMetadata? = null

    val metadata: VideoMetadata
        get() = metadataInternal ?: VideoMetadata(0, 0, 0, 0f, 0, false)

    fun open(path: String): Boolean {
        if (handle == 0L) return false
        if (!nativeOpen(handle, path)) {
            Timber.w("VideoDecoder: open failed for %s", path)
            return false
        }
        metadataInternal = readMetadata()
        return true
    }

    fun openFd(fd: Int): Boolean {
        if (handle == 0L) return false
        if (!nativeOpenFd(handle, fd)) {
            Timber.w("VideoDecoder: openFd failed for %d", fd)
            return false
        }
        metadataInternal = readMetadata()
        return true
    }

    private fun readMetadata(): VideoMetadata {
        val arr = nativeMetadata(handle)
        return VideoMetadata(
            width      = arr[0],
            height     = arr[1],
            durationMs = arr[2].toLong(),
            fps        = arr[3] / 100f,
            rotation   = arr[4],
            hasAudio   = arr[5] != 0,
        )
    }

    /** Decode the next frame into [destination]. Bitmap must be ARGB_8888 + sized to metadata. */
    fun decodeNextFrame(destination: Bitmap): DecodeResult {
        if (handle == 0L) return DecodeResult.Error
        if (destination.config != Bitmap.Config.ARGB_8888 || !destination.isMutable) {
            return DecodeResult.Error
        }
        return when (nativeDecodeFrame(handle, destination, ptsScratch)) {
            1    -> DecodeResult.Frame
            0    -> DecodeResult.EndOfStream
            else -> DecodeResult.Error
        }
    }

    /** Microsecond PTS of the most recent frame, or [Long.MIN_VALUE] if unavailable. */
    val lastFramePtsUs: Long get() = ptsScratch[0]

    /** Seek to nearest keyframe at or before [timeUs]. */
    fun seekTo(timeUs: Long): Boolean {
        if (handle == 0L) return false
        return nativeSeekTo(handle, timeUs)
    }

    /** Close the underlying file but keep the C++ object alive (so `open()` can be called again). */
    fun closeFile() {
        if (handle != 0L) {
            nativeClose(handle)
            metadataInternal = null
        }
    }

    /** Free the C++ object — after this the instance can't be reused. */
    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
            metadataInternal = null
        }
    }

    enum class DecodeResult { Frame, EndOfStream, Error }

    private external fun nativeCreate(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeOpen(handle: Long, path: String): Boolean
    private external fun nativeOpenFd(handle: Long, fd: Int): Boolean
    private external fun nativeClose(handle: Long)
    private external fun nativeMetadata(handle: Long): IntArray
    private external fun nativeSeekTo(handle: Long, timeUs: Long): Boolean
    private external fun nativeDecodeFrame(handle: Long, dst: Bitmap, outPts: LongArray): Int

    companion object {
        init { System.loadLibrary("videoedit") }
    }
}
