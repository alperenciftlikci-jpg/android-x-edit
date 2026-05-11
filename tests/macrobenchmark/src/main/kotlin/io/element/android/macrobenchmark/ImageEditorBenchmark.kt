/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark for the image editor export pipeline.
 *
 * Reads the [TraceSectionMetric] sections emitted by `:libraries:core-perf` (`trace { ... }`) inside
 * `BitmapExporter`. The same section names are used by any alternative image-editing implementation
 * so different libraries can be compared directly without changing the benchmark.
 *
 * Run on a connected device:
 * ```
 * ./gradlew :tests:macrobenchmark:connectedBenchmarkAndroidTest
 * ```
 *
 * Outputs:
 * - JSON metrics: `tests/macrobenchmark/build/outputs/connected_android_test_additional_output/`
 * - Perfetto trace: same dir, open in https://ui.perfetto.dev to inspect the flame graph.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ImageEditorBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun exportImage() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric("imageeditor.export"),
            TraceSectionMetric("imageeditor.export.draw"),
            FrameTimingMetric(),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
    ) {
        // The benchmark scenario is intentionally a stub right now: it only launches the app so the
        // section metrics return a value when the trace section is exercised by some other path.
        // Once a deeplink or test-only intent for the image editor exists, the click-through here
        // should drive the actual export flow (open editor → tap Export → wait for completion).
        startActivityAndWait()
    }

    companion object {
        private const val TARGET_PACKAGE = "io.element.android.x"
        private const val ITERATIONS = 5
    }
}
