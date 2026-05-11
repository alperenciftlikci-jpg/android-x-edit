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
 * Tracks the duration of a user gesture (drag, stroke, multi-step input) where the start and end
 * happen in different callbacks. Pair with `remember { TracedGesture("...") }` in a Composable so
 * the same instance is reused across recompositions.
 *
 * Emits both an `androidx.tracing` async section (visible in Perfetto / Studio System Trace) and
 * an entry in [PerfRegistry] (visible in the dashboard at `localhost:9999`).
 *
 * @param recordFps when true (default), records the per-gesture FPS computed as
 *   `framesDuring / wallClockDuration`. Set to **false** for *session-style* traces — multi-second
 *   spans where the screen is open but not being actively redrawn (e.g. an editor screen sitting
 *   idle, a text-entry session waiting on the IME). The frame counter only ticks when JankStats
 *   observes a real rendered frame, so an idle 10 s session reads as "20 fps" not "60 fps", which
 *   is a measurement artefact, not jank. Duration is still recorded; only the FPS column is
 *   suppressed (NaN → dashboard skips this entry from the per-section FPS aggregate).
 */
class TracedGesture(
    private val name: String,
    private val recordFps: Boolean = true,
) {
    private val cookie = name.hashCode()
    private var startNs: Long = 0L
    private var startFrames: Long = 0L

    /** Mark the start of the gesture. */
    fun start() {
        Trace.beginAsyncSection(name, cookie)
        startNs = System.nanoTime()
        startFrames = PerfRegistry.currentFrameCount()
    }

    /** Mark a successful end. Records the duration + per-gesture FPS to [PerfRegistry]. */
    fun finish() {
        val started = startNs
        if (started == 0L) return
        val durationNs = System.nanoTime() - started
        val framesDuring = (PerfRegistry.currentFrameCount() - startFrames).coerceAtLeast(0L)
        Trace.endAsyncSection(name, cookie)
        val fps = if (recordFps) fpsForRun(framesDuring, durationNs) else Float.NaN
        PerfRegistry.record(name, durationNs, fps)
        startNs = 0L
    }

    /** Mark a cancelled end. Records to [PerfRegistry] under `<name>.cancel`. */
    fun cancel() {
        val started = startNs
        if (started == 0L) return
        val durationNs = System.nanoTime() - started
        val framesDuring = (PerfRegistry.currentFrameCount() - startFrames).coerceAtLeast(0L)
        Trace.endAsyncSection(name, cookie)
        val fps = if (recordFps) fpsForRun(framesDuring, durationNs) else Float.NaN
        PerfRegistry.record("$name.cancel", durationNs, fps)
        startNs = 0L
    }
}
