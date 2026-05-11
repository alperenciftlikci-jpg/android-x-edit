/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.libraries.imageeditor.last"
}

dependencies {
    implementation(projects.libraries.corePerf)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.timber)

    // Crop / rotate / flip — uCrop (View-based, embedded via AndroidView).
    implementation(libs.ucrop)
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Drawing — Jetpack Ink for low-latency stroke authoring.
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.nativeloader)
}
