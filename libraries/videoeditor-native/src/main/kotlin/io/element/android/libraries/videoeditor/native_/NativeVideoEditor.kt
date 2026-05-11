/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_

import timber.log.Timber

/**
 * Edit parameters for [NativeVideoEditor.process]. Default value = passthrough (the source
 * file is decoded → re-encoded into the destination format with no other changes). Trim and
 * crop are applied at the decoder layer; filters / overlays will be added when we hook the
 * imageeditor-native FilterChain into the pipeline.
 *
 * Time fields are milliseconds (Kotlin idiom). `trimEndMs = -1f` means "until EOF".
 */
data class VideoEditParams(
    val trimStartMs: Float = 0f,
    val trimEndMs: Float = -1f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropW: Float = 1f,
    val cropH: Float = 1f,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val bitrateKbps: Int = 4000,
    val frameRate: Int = 30,
    val keepAudio: Boolean = true,
    // CPU-side filters (per-frame post-decode pass).
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
) {
    /** 15 floats matching the C++ `nativeProcess` layout. */
    fun toFloatArray(): FloatArray = floatArrayOf(
        trimStartMs, trimEndMs,
        cropX, cropY, cropW, cropH,
        outputWidth.toFloat(), outputHeight.toFloat(),
        bitrateKbps.toFloat(),
        // Sign of frameRate carries `keepAudio` (negative = mute).
        if (keepAudio) frameRate.toFloat() else -frameRate.toFloat(),
        exposure, brightness, contrast, saturation, warmth,
    )
}

/** Progress callback for [NativeVideoEditor.process]. `fraction` is `0..1`. */
fun interface VideoEditProgressListener {
    fun onProgress(fraction: Float)
}

/**
 * Kotlin facade for `libvideoedit.so`'s end-to-end edit pipeline. One-shot:
 *
 *   NativeVideoEditor().process(input, output, VideoEditParams(...)) { fraction -> ... }
 *
 * Synchronous on the calling thread — caller is responsible for putting it on a worker. A
 * coroutine-friendly wrapper is in `VideoEdit.kt` (TBD) so app code can `withContext(Dispatchers.IO)`.
 */
class NativeVideoEditor {
    /** Returns the linked FFmpeg version string. Useful as a cold-load smoke test. */
    fun probe(): String = nativeProbe()

    /**
     * Run the full pipeline (decode → optional trim/crop/scale → encode + audio passthrough mux).
     * Returns true on success.
     */
    fun process(
        inputPath: String,
        outputPath: String,
        params: VideoEditParams = VideoEditParams(),
        progress: VideoEditProgressListener? = null,
    ): Boolean = nativeProcess(inputPath, outputPath, params.toFloatArray(), progress)

    private external fun nativeProbe(): String
    private external fun nativeProcess(
        inputPath: String,
        outputPath: String,
        params: FloatArray,
        progress: VideoEditProgressListener?,
    ): Boolean

    companion object {
        init {
            try {
                System.loadLibrary("videoedit")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load libvideoedit.so — check FFmpeg link config")
                throw t
            }
        }
    }
}
