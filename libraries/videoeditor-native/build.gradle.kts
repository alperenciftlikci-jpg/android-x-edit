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
    namespace = "io.element.android.libraries.videoeditor.native_"

    // Path to Telegram's prebuilt FFmpeg (libavcodec/libavformat/libavutil/...). Configurable
    // via gradle property so the contributor can place the Telegram clone wherever they like:
    //   ./gradlew ... -Ptelegram.ffmpeg.dir=/abs/path/Telegram/TMessagesProj/jni/ffmpeg
    // Default points at the dev box layout; CI / clean checkouts must override.
    val ffmpegDir = (project.findProperty("telegram.ffmpeg.dir") as String?)
        ?: "C:/AlperenCiftlikciMobile/Telegram/TMessagesProj/jni/ffmpeg"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DTELEGRAM_FFMPEG_DIR=$ffmpegDir",
                )
                // arm64-v8a temporarily disabled: Telegram's prebuilt FFmpeg arm64 .a files
                // contain inline ASM with text relocations, which the lld linker rejects when
                // building a shared lib (R_AARCH64_ADR_PREL_PG_HI21 / R_AARCH64_ADD_ABS_LO12_NC).
                // To re-enable arm64 we need to rebuild FFmpeg with `--disable-asm` or rebuild
                // the affected ASM units with `-fPIC`. armeabi-v7a + x86_64 work as-is and
                // cover the dev emulator + 32-bit fallback.
                abiFilters += listOf("armeabi-v7a", "x86_64")
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

    ndkVersion = "26.1.10909125"
}

dependencies {
    implementation(projects.libraries.corePerf)
    implementation(libs.timber)
    // Media3/ExoPlayer for the video editor's preview playback. We only need core +
    // ui (PlayerView is the AndroidView we mount in Compose); transformer/effect aren't
    // pulled in because the video pipeline encode happens via our own native code.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
}
