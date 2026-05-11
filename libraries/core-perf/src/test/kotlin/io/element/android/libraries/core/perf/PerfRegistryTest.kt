/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class PerfRegistryTest {
    @After
    fun tearDown() {
        PerfRegistry.reset()
        PerfRegistry.setReporter(NoopPerfReporter)
    }

    @Test
    fun `record aggregates count, total, min and max in nanoseconds`() {
        PerfRegistry.record("foo.bar", 10_000_000) // 10 ms
        PerfRegistry.record("foo.bar", 30_000_000) // 30 ms
        PerfRegistry.record("foo.bar", 20_000_000) // 20 ms

        val stats = PerfRegistry.snapshot().getValue("foo.bar")

        assertThat(stats.count).isEqualTo(3)
        assertThat(stats.totalNs).isEqualTo(60_000_000)
        assertThat(stats.minNs).isEqualTo(10_000_000)
        assertThat(stats.maxNs).isEqualTo(30_000_000)
        assertThat(stats.averageNs).isEqualTo(20_000_000)
        // Whole-ms convenience accessors round to whole ms.
        assertThat(stats.totalMs).isEqualTo(60)
    }

    @Test
    fun `sub-millisecond observations are preserved at nanosecond precision`() {
        PerfRegistry.record("foo.fast", 230_000) // 0.23 ms
        PerfRegistry.record("foo.fast", 410_000) // 0.41 ms

        val stats = PerfRegistry.snapshot().getValue("foo.fast")

        // Whole-ms accessor rounds to 0; the ns one is intact.
        assertThat(stats.medianMs).isEqualTo(0L)
        assertThat(stats.medianNs).isAtLeast(230_000L)
    }

    @Test
    fun `median and p95 are computed from the rolling window`() {
        repeat(100) { i ->
            PerfRegistry.record("foo.bar", (i + 1) * 1_000_000L) // 1ms .. 100ms
        }
        val stats = PerfRegistry.snapshot().getValue("foo.bar")

        assertThat(stats.medianMs).isIn(40L..60L)
        assertThat(stats.p95Ms).isAtLeast(90L)
    }

    @Test
    fun `reset clears all sections`() {
        PerfRegistry.record("foo.bar", 5_000_000)
        PerfRegistry.reset()
        assertThat(PerfRegistry.snapshot()).isEmpty()
    }

    @Test
    fun `installed reporter receives every observation in milliseconds`() {
        val seen = mutableListOf<Pair<String, Long>>()
        PerfRegistry.setReporter { name, durationMs -> seen += name to durationMs }

        PerfRegistry.record("foo.bar", 7_000_000) // 7 ms
        PerfRegistry.record("foo.bar", 9_000_000) // 9 ms

        assertThat(seen).containsExactly("foo.bar" to 7L, "foo.bar" to 9L).inOrder()
    }
}
