/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.videoeditor.native_

import timber.log.Timber

/**
 * Kotlin facade for the C++ FFmpeg-based stream-copy trim implemented in
 * `cpp/pipeline/StreamCopyTrim.cpp`. Alternative to the Java `Mp4TrimEngine` —
 * useful for A/B testing performance and codec compatibility, especially on emulators
 * where Android's MediaExtractor returns Annex-B for MP4 sources and trips up the
 * Java path.
 *
 * Pure stream copy: no decode, no encode. Bound by I/O speed.
 *
 * ABI note: libvideoedit.so is only built for armeabi-v7a + x86_64 today (Telegram's
 * prebuilt FFmpeg arm64 .a files have text relocations that the lld linker rejects).
 * Calls into this class on arm64 will throw `UnsatisfiedLinkError` — wrap callers in
 * a try/catch and fall back to the Java path.
 */
class NativeStreamCopyTrim {

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    data class Result(
        val success: Boolean,
        val bytesWritten: Long,
        val packetsWritten: Int,
        val firstPtsUs: Long,
        val lastPtsUs: Long,
        val error: String,
    )

    /**
     * Trim [inputPath] to [outputPath] keeping samples in `[startUs, endUs)`.
     * Pass `endUs = 0` to trim until EOF.
     *
     * Returns details about the export (success flag, byte count, packet count, pts
     * bounds, error message on failure). Caller is responsible for putting this call
     * on a worker thread — it blocks until the trailer is written.
     */
    fun trim(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        progress: ProgressListener? = null,
    ): Result {
        val arr = nativeStreamCopyTrim(inputPath, outputPath, startUs, endUs, progress)
        val errMsg = if (arr[0] == 0L) nativeStreamCopyTrimLastError() else ""
        return Result(
            success = arr[0] == 1L,
            bytesWritten = arr[1],
            packetsWritten = arr[2].toInt(),
            firstPtsUs = arr[3],
            lastPtsUs = arr[4],
            error = errMsg,
        )
    }

    private external fun nativeStreamCopyTrim(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        progress: ProgressListener?,
    ): LongArray

    private external fun nativeStreamCopyTrimLastError(): String

    companion object {
        init {
            try {
                System.loadLibrary("videoedit")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load libvideoedit.so — check ABI / FFmpeg link config")
                throw t
            }
        }
    }
}
