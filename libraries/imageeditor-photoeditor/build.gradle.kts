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
    namespace = "io.element.android.libraries.imageeditor.photoeditor"
}

dependencies {
    implementation(projects.libraries.corePerf)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.timber)

    // burhanrashid52/PhotoEditor — all-in-one editor: drawing, text, emoji, filters, shapes,
    // pinch/scale/rotate, undo/redo. View-based; embedded inline via AndroidView for the
    // third A/B comparison point (alongside our baseline and the uCrop+Ink modular editor).
    implementation(libs.photoeditor)
    // PhotoEditor extends AppCompatImageView internally.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
