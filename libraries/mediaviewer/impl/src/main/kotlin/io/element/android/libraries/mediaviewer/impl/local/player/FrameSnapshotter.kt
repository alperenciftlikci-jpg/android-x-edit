/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a single frame from a video URI at a given timestamp and writes it
 * into the device gallery (Pictures/ElementSnapshots/).
 *
 * Why MediaMetadataRetriever and not TextureView.getBitmap(): the player uses
 * a SurfaceView (default for ExoPlayer's PlayerView), which can't be sampled
 * from the View hierarchy. We could swap to TextureView for snapshot support,
 * but that costs an extra GPU copy on every frame of every video — a steep
 * price for a feature that fires once on a button tap. Re-decoding via
 * MediaMetadataRetriever takes 100-500ms but doesn't touch the playback path.
 */
object FrameSnapshotter {
    suspend fun capture(
        context: Context,
        videoUri: Uri,
        positionMillis: Long,
        targetAspectRatio: Float? = null,
        maxDimensionPx: Int? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        val frame = decodeFrame(context, videoUri, positionMillis, maxDimensionPx) ?: return@withContext null
        try {
            // Respect the resize-mode aspect ratio: if the user is
            // viewing the video in 3:4 / 4:3 / 9:16 / 16:9, the snapshot
            // should come out in the same shape (centre-cropped from
            // the native frame). null = no override, save the frame as-is.
            val output = if (targetAspectRatio != null) {
                val cropped = cropToAspectRatio(frame, targetAspectRatio)
                if (cropped !== frame) frame.recycle()
                cropped
            } else {
                frame
            }
            try {
                writeBitmapToGallery(context, output)
            } finally {
                if (output !== frame) output.recycle()
            }
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    /**
     * Centre-crop `src` to the target aspect ratio (width / height).
     * Returns the same bitmap if it already matches (no crop needed).
     */
    private fun cropToAspectRatio(src: Bitmap, targetAspect: Float): Bitmap {
        val srcW = src.width
        val srcH = src.height
        if (srcW == 0 || srcH == 0) return src
        val srcAspect = srcW.toFloat() / srcH
        // Within 1 % of target — close enough, no crop saves a copy.
        if (kotlin.math.abs(srcAspect - targetAspect) / targetAspect < 0.01f) return src
        return if (srcAspect > targetAspect) {
            // Source is wider than target → crop left + right.
            val newW = (srcH * targetAspect).toInt().coerceAtLeast(1)
            val xOff = ((srcW - newW) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(src, xOff, 0, newW, srcH)
        } else {
            // Source is taller → crop top + bottom.
            val newH = (srcW / targetAspect).toInt().coerceAtLeast(1)
            val yOff = ((srcH - newH) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(src, 0, yOff, srcW, newH)
        }
    }

    private fun decodeFrame(
        context: Context,
        videoUri: Uri,
        positionMillis: Long,
        maxDimensionPx: Int?,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            val timeUs = positionMillis * 1000L
            // OPTION_CLOSEST_SYNC jumps to the nearest keyframe instead of
            // decoding the exact requested frame — 3-5x faster (typically
            // 30-100 ms vs 200-500 ms with OPTION_CLOSEST). The trade-off
            // is the saved frame can be up to a GOP-length off (rarely
            // more than 1-2 s); for a quick "screenshot now" affordance
            // the speed win matters more than millisecond-perfect timing.
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC

            // 4K-aware fast path: if the video is larger than the device's
            // long-edge screen pixel count, hand MediaMetadataRetriever a
            // dst size up front via getScaledFrameAtTime (API 27+) so the
            // decoder downsamples during decode instead of giving us the
            // full 3840×2160 ≈ 33 MB ARGB_8888 bitmap that we then crop /
            // re-encode. Saves ~4–8× peak memory + decode time on 4K
            // sources. Falls back to getFrameAtTime when there's no
            // dst-size cap, on API < 27, or when the source already fits
            // within the cap.
            val scaledFrame = if (maxDimensionPx != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val srcW = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val srcH = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                if (srcW > 0 && srcH > 0 && (srcW > maxDimensionPx || srcH > maxDimensionPx)) {
                    val scale = maxDimensionPx.toFloat() / maxOf(srcW, srcH).toFloat()
                    val dstW = (srcW * scale).toInt().coerceAtLeast(1)
                    val dstH = (srcH * scale).toInt().coerceAtLeast(1)
                    retriever.getScaledFrameAtTime(timeUs, option, dstW, dstH)
                } else null
            } else null
            scaledFrame ?: retriever.getFrameAtTime(timeUs, option)
        } catch (t: Throwable) {
            Timber.e(t, "FrameSnapshotter: getFrameAtTime failed")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeBitmapToGallery(context: Context, bitmap: Bitmap): Uri? {
        val filename = "snapshot_${System.currentTimeMillis()}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, bitmap, filename)
        } else {
            writeViaLegacyFile(context, bitmap, filename)
        }
    }

    private fun writeViaMediaStore(context: Context, bitmap: Bitmap, filename: String): Uri? {
        // Scoped Storage path (Android 10+): no WRITE_EXTERNAL_STORAGE needed.
        // RELATIVE_PATH lands the file under DCIM/<subdir> so it shows up in
        // the system gallery + dedicated app folder without extra MediaScanner
        // broadcasts (MediaStore.insert does the registration for us).
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ElementSnapshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Timber.e("FrameSnapshotter: MediaStore.insert returned null")
            return null
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    Timber.e("FrameSnapshotter: JPEG compress returned false")
                    resolver.delete(uri, null, null)
                    return null
                }
            } ?: run {
                Timber.e("FrameSnapshotter: openOutputStream returned null for $uri")
                resolver.delete(uri, null, null)
                return null
            }
            // Flip IS_PENDING off so the gallery picks the file up. Without this
            // the row stays hidden indefinitely (orphans only get cleaned up
            // after a 7-day grace period by the system).
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            Timber.e(t, "FrameSnapshotter: failed to write snapshot bitmap")
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    private fun writeViaLegacyFile(context: Context, bitmap: Bitmap, filename: String): Uri? {
        // Android 9 fallback. Caller is expected to have already obtained
        // WRITE_EXTERNAL_STORAGE — if not, this throws SecurityException and
        // returns null (snapshot button is a permission prompt entry point on
        // these older OSes — see SnapshotPermissionGate in MediaVideoView).
        return try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ElementSnapshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            // Tell the system MediaScanner about the new file so it appears in
            // Gallery — MediaStore on pre-Q doesn't index direct file writes.
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null,
            )
            Uri.fromFile(file)
        } catch (t: Throwable) {
            Timber.e(t, "FrameSnapshotter: legacy file write failed")
            null
        }
    }

    // 85 is the perception threshold for photos / video stills — humans
    // can't tell 85 from 100 in a blind test, but the JPEG encoder runs
    // ~30 % faster than at 92 and the file is also smaller. The previous
    // 92 was visually identical but cost more CPU on the snapshot path,
    // which the user noticed as snapshot lag.
    private const val JPEG_QUALITY = 85
}
