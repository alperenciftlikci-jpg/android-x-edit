/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

import android.app.Application
import androidx.compose.material3.ComposeMaterial3Flags.isAnchoredDraggableComponentsStrictOffsetCheckEnabled
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.startup.AppInitializer
import androidx.work.Configuration
import dev.zacsweers.metro.createGraphFactory
import io.element.android.libraries.core.perf.FpsSampler
import io.element.android.libraries.core.perf.JankCollector
import io.element.android.libraries.core.perf.MatrixBridge
import io.element.android.libraries.core.perf.MemoryCollector
import io.element.android.libraries.core.perf.PerfHttpServer
import io.element.android.libraries.core.perf.PerfRegistry
import io.element.android.libraries.di.DependencyInjectionGraphOwner
import io.element.android.libraries.workmanager.api.di.MetroWorkerFactory
import io.element.android.x.BuildConfig
import io.element.android.x.di.AppGraph
import io.element.android.x.info.logApplicationInfo
import io.element.android.x.initializer.CacheCleanerInitializer
import io.element.android.x.initializer.CrashInitializer
import io.element.android.x.initializer.PlatformInitializer

class ElementXApplication : Application(), DependencyInjectionGraphOwner, Configuration.Provider {
    override val graph: AppGraph = createGraphFactory<AppGraph.Factory>().create(this)

    override val workManagerConfiguration: Configuration = Configuration.Builder()
        .setWorkerFactory(MetroWorkerFactory(graph.workerProviders))
        .build()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate() {
        super.onCreate()
        AppInitializer.getInstance(this).apply {
            initializeComponent(CrashInitializer::class.java)
            initializeComponent(PlatformInitializer::class.java)
            initializeComponent(CacheCleanerInitializer::class.java)
        }

        // Debug-only perf HUD: collects JankStats + Tencent Matrix issues, and serves a localhost
        // HTML report at :9999. See docs/perf_tracing.md for `adb forward` workflow.
        if (BuildConfig.DEBUG) {
            JankCollector.start(this)
            MatrixBridge.start(this)
            MemoryCollector.start()
            FpsSampler.start()
            // Declare per-library FPS SLAs — these surface as PASS/FAIL panels at the top of the
            // perf dashboard's FPS section. Edit the numbers below to match the budget you've
            // committed to. Longest-matching prefix wins when prefixes overlap.
            PerfRegistry.declareSla(prefix = "imageeditor", minFps = 60f, targetFps = 75f, label = "Baseline editor")
            PerfRegistry.declareSla(prefix = "imageeditor.modular", minFps = 60f, targetFps = 75f, label = "Modular (uCrop + Jetpack Ink)")
            PerfRegistry.declareSla(prefix = "imageeditor.photoeditor", minFps = 60f, targetFps = 75f, label = "PhotoEditor (burhanrashid52)")
            PerfHttpServer.start(this)
        }

        logApplicationInfo(this)

        // Disable the strict offset check for anchored draggable components, as it can cause issues with bottom sheets.
        // Remove once https://issuetracker.google.com/issues/477038695 is fixed.
        isAnchoredDraggableComponentsStrictOffsetCheckEnabled = false
    }
}
