/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.libraries.imageeditor.modular"
}

dependencies {
    implementation(projects.libraries.corePerf)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.timber)

    // External image-editing libraries we want to A/B against the baseline.
    // uCrop (View-based) — embedded inline via AndroidView for the crop tool.
    // uCrop's `GestureCropImageView` extends AppCompatImageView, hence the explicit appcompat dep.
    implementation(libs.ucrop)
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Jetpack Ink API — Compose-native low-latency stroke authoring.
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.nativeloader)
}
