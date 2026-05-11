/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

/**
 * Plug point for forwarding section timings to an external system (e.g. Sentry, Datadog).
 * Default is [NoopPerfReporter] — install a custom one via [PerfRegistry.setReporter].
 *
 * Implementations are called on the thread that closed the trace section, so they MUST NOT block.
 */
fun interface PerfReporter {
    fun onSection(name: String, durationMs: Long)
}

object NoopPerfReporter : PerfReporter {
    override fun onSection(name: String, durationMs: Long) = Unit
}
