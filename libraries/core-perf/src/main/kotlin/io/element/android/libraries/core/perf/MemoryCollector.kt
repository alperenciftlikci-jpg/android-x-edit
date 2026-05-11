/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodically samples the process's heap and native heap usage and pushes the result into
 * [PerfRegistry.recordMemory]. Runs on the default dispatcher; cheap (~1 ms per sample), 1 Hz
 * default. Debug-only — call [start] from `Application.onCreate()` behind a `BuildConfig.DEBUG`
 * gate.
 *
 * What we sample:
 * - **Java heap used** — `Runtime.getRuntime().totalMemory() - freeMemory()`. Climbing trend with
 *   no reset = leak suspicion in JVM allocations.
 * - **Java heap max** — `Runtime.getRuntime().maxMemory()`. Process limit before OOM.
 * - **Native heap allocated** — `Debug.getNativeHeapAllocatedSize()`. Important for image apps —
 *   bitmap allocations land here.
 * - **Total PSS** — `Debug.MemoryInfo.totalPss * 1024`. The most realistic "how much RAM is this
 *   process using overall" number.
 *
 * Activity-leak detection itself is delegated to Tencent Matrix's ResourceCanary (via
 * [MatrixBridge]); that catches retained Activity instances and feeds them into the dashboard's
 * `Otomatik tanı` panel as `💧` findings.
 */
object MemoryCollector {
    private const val TAG = "MemoryCollector"
    private const val SAMPLE_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.i(TAG, "Started — sampling every ${SAMPLE_INTERVAL_MS}ms")
            while (isActive) {
                sampleOnce()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sampleOnce() {
        try {
            val rt = Runtime.getRuntime()
            val javaUsedBytes = rt.totalMemory() - rt.freeMemory()
            val javaMaxBytes = rt.maxMemory()
            val nativeBytes = Debug.getNativeHeapAllocatedSize()
            // PSS via Debug.MemoryInfo. Slightly more expensive (~few ms) but only 1 Hz.
            val pssKb = runCatching {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                info.totalPss.toLong()
            }.getOrDefault(0L)

            PerfRegistry.recordMemory(
                PerfRegistry.MemorySnapshot(
                    timestampMs = System.currentTimeMillis(),
                    javaHeapUsedMb = javaUsedBytes / MIB,
                    javaHeapMaxMb = javaMaxBytes / MIB,
                    nativeHeapAllocatedMb = nativeBytes / MIB,
                    totalPssMb = pssKb / 1024L,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Sample failed", t)
        }
    }

    private const val MIB = 1_048_576L
}
