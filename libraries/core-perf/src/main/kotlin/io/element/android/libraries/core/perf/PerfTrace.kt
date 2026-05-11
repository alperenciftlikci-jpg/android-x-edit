/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import androidx.tracing.Trace

/**
 * Run [block] inside an `androidx.tracing` section so it shows up in Perfetto / Studio System Trace,
 * and record the duration in [PerfRegistry] for in-app dev histograms.
 *
 * Use the convention `<module>.<operation>[.<step>]` for [section] (e.g. `imageeditor.export`,
 * `imageeditor.export.draw`) so different implementations of the same operation can be compared
 * by Macrobenchmark `TraceSectionMetric` without changing the benchmark code.
 *
 * Synchronous; for suspend code use [traceAsync].
 */
inline fun <T> trace(section: String, block: () -> T): T {
    Trace.beginSection(section)
    val startNs = System.nanoTime()
    val startFrames = PerfRegistry.currentFrameCount()
    try {
        return block()
    } finally {
        val durationNs = System.nanoTime() - startNs
        val framesDuring = (PerfRegistry.currentFrameCount() - startFrames).coerceAtLeast(0L)
        Trace.endSection()
        PerfRegistry.record(section, durationNs, fpsForRun(framesDuring, durationNs))
    }
}

/**
 * Frames captured during the section over wall-clock duration → FPS. Returns [Float.NaN] when
 * the section was too short for a stable measurement.
 *
 * Why the thresholds:
 *  - 1 frame in a 0.5 ms window literally yields 2000 fps, which is meaningless — the frame
 *    itself was 16 ms long and only its tail overlapped our trace span. We need at least
 *    **2 frames** so the ratio reflects an actual frame *interval* and not a single sample.
 *  - **33 ms** (~ 2 frame intervals at 60 Hz) is the floor below which the math hasn't had a
 *    chance to converge — even with 2 frames, a 5 ms span yields 400 fps which is noise.
 *
 * Anything passing both filters is a real "frames per wall-clock second" reading.
 */
@PublishedApi
internal fun fpsForRun(framesDuring: Long, durationNs: Long): Float {
    if (framesDuring < 2L || durationNs < 33_000_000L) return Float.NaN
    val fps = (framesDuring.toFloat() * 1_000_000_000f) / durationNs.toFloat()
    // Defensive cap: 240 covers all current display refresh rates (60/90/120/144/240). Anything
    // higher is a sampling artifact even after the duration check.
    return fps.coerceAtMost(240f)
}

/**
 * Coroutine-safe variant. Uses an async section so concurrent invocations of the same name don't
 * collide on the trace stack. The cookie is derived from `section.hashCode()`; if you need two
 * concurrent invocations of the *same* section name distinguishable, pass a different [cookie].
 */
suspend inline fun <T> traceAsync(
    section: String,
    cookie: Int = section.hashCode(),
    crossinline block: suspend () -> T,
): T {
    Trace.beginAsyncSection(section, cookie)
    val startNs = System.nanoTime()
    val startFrames = PerfRegistry.currentFrameCount()
    try {
        return block()
    } finally {
        val durationNs = System.nanoTime() - startNs
        val framesDuring = (PerfRegistry.currentFrameCount() - startFrames).coerceAtLeast(0L)
        Trace.endAsyncSection(section, cookie)
        PerfRegistry.record(section, durationNs, fpsForRun(framesDuring, durationNs))
    }
}
