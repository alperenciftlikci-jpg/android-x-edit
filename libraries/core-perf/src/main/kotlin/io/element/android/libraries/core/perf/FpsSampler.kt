/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wall-clock live FPS sampler — runs at 1 Hz, computes
 * `framesCapturedThisSecond / 1.0s` from [PerfRegistry.currentFrameCount],
 * pushes the result into [PerfRegistry.recordFpsSample] for the dashboard's live chart.
 *
 * **Frames here are real rendered frames as observed by JankStats** — meaning Choreographer
 * actually scheduled and rendered them. When the screen is static (no animations, no input,
 * no recomposition) Android intentionally doesn't produce frames; the FPS sample for that second
 * will be near zero. That's the device's actual rendering rate, not a bug.
 *
 * Debug-only — call [start] from `Application.onCreate()` behind a `BuildConfig.DEBUG` gate.
 */
object FpsSampler {
    private const val TAG = "FpsSampler"
    private const val SAMPLE_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i(TAG, "Started — wall-clock FPS sampling at 1 Hz")
            var lastFrames = PerfRegistry.currentFrameCount()
            var lastTimeMs = System.currentTimeMillis()
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val nowMs = System.currentTimeMillis()
                val nowFrames = PerfRegistry.currentFrameCount()
                val deltaFrames = (nowFrames - lastFrames).coerceAtLeast(0L)
                val deltaTimeMs = (nowMs - lastTimeMs).coerceAtLeast(1L)
                val fps = (deltaFrames * 1_000f) / deltaTimeMs.toFloat()
                PerfRegistry.recordFpsSample(nowMs, fps)
                lastFrames = nowFrames
                lastTimeMs = nowMs
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
