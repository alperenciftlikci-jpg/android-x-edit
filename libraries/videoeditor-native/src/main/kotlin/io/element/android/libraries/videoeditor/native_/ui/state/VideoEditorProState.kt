/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui.state

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.element.android.libraries.videoeditor.native_.NativeVideoDecoder
import io.element.android.libraries.videoeditor.native_.VideoEditParams
import io.element.android.libraries.videoeditor.native_.VideoMetadata

/**
 * State holder for the Telegram-style video editor screen.
 *
 *  - `metadata`: source video info loaded once on init.
 *  - `thumbnails`: a SnapshotStateList of evenly-spaced frame previews; populated
 *    asynchronously by [extractThumbnails] using NativeVideoDecoder.
 *  - `trimStart` / `trimEnd`: 0..1 normalised positions over the full duration.
 *    Convert to ms via `state.trimStartMs / state.trimEndMs`.
 *  - `currentScrubFraction`: where the user is dragging in the timeline (0..1).
 *    The screen renders the corresponding decoded frame as a preview.
 */
@Stable
class VideoEditorProState {
    var metadata: VideoMetadata by mutableStateOf(VideoMetadata(0, 0, 0L, 0f, 0, false))
        internal set

    val thumbnails: SnapshotStateList<Bitmap> = mutableStateListOf()

    var trimStart: Float by mutableStateOf(0f)
    var trimEnd: Float   by mutableStateOf(1f)

    var currentScrubFraction: Float by mutableStateOf(0f)
    var scrubbedFrame: Bitmap? by mutableStateOf(null)
        internal set

    var isExporting: Boolean by mutableStateOf(false)
        internal set
    var exportProgress: Float by mutableStateOf(0f)
        internal set

    // Crop rectangle in normalised image-space.
    var cropX: Float by mutableStateOf(0f)
    var cropY: Float by mutableStateOf(0f)
    var cropW: Float by mutableStateOf(1f)
    var cropH: Float by mutableStateOf(1f)
    /** Optional aspect ratio lock (null = free crop). */
    var cropAspectLocked: Float? by mutableStateOf(null)

    // Mute toggle.
    var muteAudio: Boolean by mutableStateOf(false)

    // Selected output bitrate quality (low/med/high → 2/4/8 Mbps).
    var qualityKbps: Int by mutableStateOf(4000)

    // Filter sliders — CPU-side, applied per-frame in the export pipeline.
    var exposure: Float by mutableStateOf(0f)
    var brightness: Float by mutableStateOf(0f)
    var contrast: Float by mutableStateOf(1f)
    var saturation: Float by mutableStateOf(1f)
    var warmth: Float by mutableStateOf(0f)

    // Playback state — ExoPlayer-driven.
    var isPlaying: Boolean by mutableStateOf(false)
    var currentPlaybackMs: Long by mutableStateOf(0L)

    var selectedTab: Tab by mutableStateOf(Tab.Trim)

    val trimStartMs: Float get() = trimStart * metadata.durationMs
    val trimEndMs:   Float get() = trimEnd   * metadata.durationMs

    /** Convert state into a [VideoEditParams] ready for `NativeVideoEditor.process`. */
    fun toEditParams(): VideoEditParams = VideoEditParams(
        trimStartMs = trimStartMs,
        trimEndMs   = trimEndMs,
        cropX = cropX, cropY = cropY, cropW = cropW, cropH = cropH,
        outputWidth = (metadata.width  * cropW).toInt().coerceAtLeast(2),
        outputHeight = (metadata.height * cropH).toInt().coerceAtLeast(2),
        bitrateKbps = qualityKbps,
        frameRate = metadata.fps.toInt().coerceAtLeast(15),
        keepAudio = !muteAudio,
        exposure = exposure,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        warmth = warmth,
    )

    fun resetFilters() {
        exposure = 0f; brightness = 0f; contrast = 1f; saturation = 1f; warmth = 0f
    }

    fun setCropRect(x: Float, y: Float, w: Float, h: Float) {
        cropX = x; cropY = y; cropW = w; cropH = h
    }
    fun setCropAspect(aspect: Float?) {
        cropAspectLocked = aspect
        if (aspect != null && aspect > 0f) {
            // Snap the rect to the new aspect, centred on the existing midpoint.
            val cx = cropX + cropW / 2f
            val cy = cropY + cropH / 2f
            val newW: Float; val newH: Float
            if (aspect >= 1f) { newW = 1f.coerceAtMost(cropH * aspect); newH = newW / aspect }
            else              { newH = 1f.coerceAtMost(cropW / aspect); newW = newH * aspect }
            cropX = (cx - newW / 2f).coerceIn(0f, 1f - newW)
            cropY = (cy - newH / 2f).coerceIn(0f, 1f - newH)
            cropW = newW.coerceAtMost(1f)
            cropH = newH.coerceAtMost(1f)
        }
    }
    fun resetCrop() { cropX = 0f; cropY = 0f; cropW = 1f; cropH = 1f; cropAspectLocked = null }

    enum class Tab { Trim, Crop, Filters, Quality }

    suspend fun extractThumbnails(decoder: NativeVideoDecoder, count: Int = 12) {
        if (!decoder.isOpen) return
        val md = decoder.metadata
        if (md.isEmpty || md.durationMs <= 0) return
        thumbnails.clear()
        // Walk N evenly-spaced timestamps. Each thumb decode = seek + decode-one — sub-
        // optimal because we re-seek per frame, but Telegram uses the same approach and
        // the user only sees this once at open.
        val cellMs = md.durationMs / count
        for (i in 0 until count) {
            val timeUs = (cellMs * i + cellMs / 2) * 1000L
            decoder.seekTo(timeUs)
            val bm = Bitmap.createBitmap(md.width, md.height, Bitmap.Config.ARGB_8888)
            // Decoder may emit a few frames before reaching the seek target — pull until
            // we get one whose PTS is in range, or just take the first decoded frame.
            val r = decoder.decodeNextFrame(bm)
            if (r == NativeVideoDecoder.DecodeResult.Frame) {
                // Down-scale to thumbnail size to bound memory (12 frames × full-res
                // bitmap would chew tens of MB on a 4K source).
                val scaled = Bitmap.createScaledBitmap(bm, 96, 54, true)
                bm.recycle()
                thumbnails.add(scaled)
            }
        }
    }

    suspend fun updateScrubbedFrame(decoder: NativeVideoDecoder) {
        if (!decoder.isOpen) return
        val md = decoder.metadata
        val timeUs = (currentScrubFraction * md.durationMs * 1000L).toLong()
        decoder.seekTo(timeUs)
        val bm = Bitmap.createBitmap(md.width, md.height, Bitmap.Config.ARGB_8888)
        if (decoder.decodeNextFrame(bm) == NativeVideoDecoder.DecodeResult.Frame) {
            scrubbedFrame = bm
        } else {
            bm.recycle()
        }
    }
}

@Composable
fun rememberVideoEditorProState(): VideoEditorProState =
    remember { VideoEditorProState() }
