/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.element.android.macrobenchmark"
    compileSdk = Versions.COMPILE_SDK

    defaultConfig {
        // Macrobenchmark requires API 28+. Devices below this won't run the benchmark.
        minSdk = 28
        targetSdk = Versions.TARGET_SDK
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Macrobenchmark needs a non-debuggable, profileable, R8-optimized variant.
        // We mirror :app's release config and only enable the `benchmark` test variant.
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

kotlin {
    jvmToolchain {
        languageVersion = Versions.javaLanguageVersion
    }
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the `benchmark` variant should be built / instrumented.
        it.enable = it.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.test.junit)
    implementation(libs.androidx.test.ext.junit)
}
