/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.libraries.core.perf"
}

dependencies {
    api(libs.androidx.tracing.ktx)
    implementation(libs.androidx.metrics.performance)
    implementation(libs.coroutines.core)

    // Tencent Matrix — only loaded into the debug variant. The release variant of this library
    // ships a no-op MatrixBridge stub so production APKs don't pull in Matrix or its native libs.
    debugImplementation(libs.matrix.android.lib)
    debugImplementation(libs.matrix.android.commons)
    debugImplementation(libs.matrix.resource.canary.android)
    debugImplementation(libs.matrix.resource.canary.common)
    debugImplementation(libs.matrix.io.canary)
    debugImplementation(libs.matrix.trace.canary)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.truth)
    testImplementation(libs.coroutines.test)
}
