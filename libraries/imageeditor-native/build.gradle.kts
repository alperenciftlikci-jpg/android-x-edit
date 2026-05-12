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
    namespace = "io.element.android.libraries.imageeditor.native_"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                )
                // arm64-v8a covers all modern phones; the others are kept so the dev
                // emulator (x86_64) and older devices still build. Drop armeabi-v7a if
                // we decide minSdk should be 28+.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The NDK version pinned here is the one currently installed on the dev box
    // (see local.properties). Other devs need at least 26+ for C++20 ranges that
    // we may add later, so we keep 26.1 as the floor.
    ndkVersion = "26.1.10909125"

    packaging {
        // Two SO files would otherwise collide with the Compose runtime's bundled libs
        // — kept out of an explicit picksFirst because we don't ship any conflicts here.
    }
}

dependencies {
    implementation(projects.libraries.corePerf)
    // SpoilerOverlay lives in :libraries:designsystem so the chat bubble can reuse the
    // exact same composable the editor uses for preview.
    implementation(projects.libraries.designsystem)
    implementation(libs.timber)
    implementation(libs.androidx.compose.material.icons)
}
