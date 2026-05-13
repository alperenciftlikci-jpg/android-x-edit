/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
import extension.readLocalProperty

plugins {
    id("io.element.android-compose-library")
}

// Resolve the Telegram-FFmpeg path from (in order): -P override, local.properties,
// environment, or sibling Telegram clone. Returns null if NONE are present — when null,
// we skip the native build entirely so a fresh checkout on a machine without the FFmpeg
// dependency still builds and launches the app (video editor's native paths just won't
// function at runtime). The user requested this graceful path so the app can ship on
// other machines without per-machine setup.
val ffmpegDir: String? = run {
    val fromProperty = project.findProperty("telegram.ffmpeg.dir") as String?
    if (!fromProperty.isNullOrBlank() && file(fromProperty).exists()) return@run fromProperty

    val fromLocal = readLocalProperty("telegram.ffmpeg.dir")
    if (!fromLocal.isNullOrBlank() && file(fromLocal).exists()) return@run fromLocal

    val fromEnv = System.getenv("TELEGRAM_FFMPEG_DIR")
    if (!fromEnv.isNullOrBlank() && file(fromEnv).exists()) return@run fromEnv

    val sibling = rootProject.projectDir.parentFile
        ?.resolve("Telegram/TMessagesProj/jni/ffmpeg")
    if (sibling != null && sibling.exists()) return@run sibling.absolutePath

    logger.warn(
        "[:libraries:videoeditor-native] Telegram FFmpeg dir not found — skipping native " +
        "build. Video editor's native code paths (decoder / encoder / stream-copy trim) " +
        "will throw UnsatisfiedLinkError at runtime. To enable, set telegram.ffmpeg.dir " +
        "in local.properties or TELEGRAM_FFMPEG_DIR env var."
    )
    null
}

android {
    namespace = "io.element.android.libraries.videoeditor.native_"

    defaultConfig {
        if (ffmpegDir != null) {
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
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    if (ffmpegDir != null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    ndkVersion = "26.1.10909125"
}

dependencies {
    implementation(projects.libraries.corePerf)
    implementation(libs.timber)
    // Media3/ExoPlayer for the preview player + Transformer for fast trim export
    // (`experimentalSetTrimOptimizationEnabled` cuts at the keyframe boundary via stream
    // copy + re-encodes only the leading GOP when the requested start sits mid-GOP).
    // Works on every ABI since it goes through Android's MediaCodec/MediaMuxer.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.transformer)
    // Telegram-style fast-trim path — direct MP4 atom writer (mdat + moov) that bypasses
    // Android MediaMuxer's JNI marshalling overhead. Ported from Telegram's MP4Builder.
    implementation(libs.mp4parser.isoparser)
}
