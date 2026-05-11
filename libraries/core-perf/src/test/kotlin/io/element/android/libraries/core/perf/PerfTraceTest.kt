/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class PerfTraceTest {
    @After
    fun tearDown() {
        PerfRegistry.reset()
    }

    @Test
    fun `trace records the section once and returns the block result`() {
        val result = trace("trace.test.sync") { 42 }

        assertThat(result).isEqualTo(42)
        val stats = PerfRegistry.snapshot().getValue("trace.test.sync")
        assertThat(stats.count).isEqualTo(1L)
    }

    @Test
    fun `trace records the section even when the block throws`() {
        runCatching { trace<Unit>("trace.test.throws") { error("boom") } }

        val stats = PerfRegistry.snapshot().getValue("trace.test.throws")
        assertThat(stats.count).isEqualTo(1L)
    }

    @Test
    fun `traceAsync records the section`() = runTest {
        val result = traceAsync("trace.test.async") { "ok" }

        assertThat(result).isEqualTo("ok")
        val stats = PerfRegistry.snapshot().getValue("trace.test.async")
        assertThat(stats.count).isEqualTo(1L)
    }
}
