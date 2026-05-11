/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import android.app.Application

/**
 * Release-variant stub for [MatrixBridge]. The real Matrix integration lives in `src/debug/`;
 * production APKs do not pull in Tencent Matrix or its native libraries.
 */
object MatrixBridge {
    fun start(application: Application) {
        // No-op in release builds.
    }
}
