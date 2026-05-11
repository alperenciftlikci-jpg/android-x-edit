/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.JankStats
import java.util.WeakHashMap

/**
 * Attaches a [JankStats] instance to every activity window so dropped/janky frames are pumped into
 * [PerfRegistry.recordFrame]. Debug-only; install once at application startup, e.g.:
 * ```
 * if (BuildConfig.DEBUG) JankCollector.start(application)
 * ```
 *
 * No-op if already started.
 */
object JankCollector {
    private val attached = WeakHashMap<Activity, JankStats>()
    @Volatile
    private var started = false

    fun start(application: Application) {
        if (started) return
        started = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                val existing = attached[activity]
                if (existing != null) {
                    // Re-enable tracking — we paused it in onActivityPaused. Without this, the
                    // very first pause/resume cycle silently kills the frame stream and the
                    // dashboard FPS would stay at 0 for the rest of the session.
                    existing.isTrackingEnabled = true
                    return
                }
                val window = activity.window ?: return
                val stats = JankStats.createAndTrack(window) { frameData ->
                    val state = frameData.states.firstOrNull()?.let { "${it.key}=${it.value}" }
                    // Pick the most accurate frame duration field available. On API 31+ the
                    // Total field includes RenderThread + GPU; on 24+ Cpu is non-GPU work; we
                    // fall back to UI for older devices.
                    val durationNs = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && frameData is FrameDataApi31 ->
                            frameData.frameDurationTotalNanos
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && frameData is FrameDataApi24 ->
                            frameData.frameDurationCpuNanos
                        else -> frameData.frameDurationUiNanos
                    }.coerceAtLeast(0L)
                    PerfRegistry.recordFrame(
                        durationNs = durationNs,
                        isJanky = frameData.isJank,
                        uiState = state,
                    )
                }
                stats.isTrackingEnabled = true
                attached[activity] = stats
            }

            override fun onActivityPaused(activity: Activity) {
                attached[activity]?.isTrackingEnabled = false
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                attached.remove(activity)
            }
        })
    }
}
