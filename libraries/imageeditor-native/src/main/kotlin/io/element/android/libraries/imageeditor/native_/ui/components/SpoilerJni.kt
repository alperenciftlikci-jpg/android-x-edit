/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import android.graphics.Bitmap
import timber.log.Timber

/**
 * Thin JNI wrapper around the native spoiler pipeline. The actual algorithm lives in
 * `cpp/spoiler/StackBlur.cpp` (Mario Klingemann's stack blur, same one Telegram-Android
 * ships natively at `image.cpp:stackBlurBitmap`). Going through JNI moves the blur off
 * the JVM, so spoiler bubbles flickering in/out of LazyColumn don't allocate `IntArray`s
 * on the Kotlin side per scroll position.
 *
 * The library is best-effort loaded: if libphotoedit.so isn't available (e.g. the build
 * skipped the native pipeline because the FFmpeg dir wasn't configured on this machine —
 * see `videoeditor-native/build.gradle.kts` for the parallel pattern), [available] flips
 * false and [stackBlur] becomes a no-op. Callers should fall back to [StackBlur.blur].
 */
internal object SpoilerJni {
    val available: Boolean = try {
        System.loadLibrary("photoedit")
        true
    } catch (t: Throwable) {
        Timber.w(t, "SpoilerJni: libphotoedit.so unavailable, falling back to Kotlin stack-blur")
        false
    }

    /** No-op when [available] is false; otherwise mutates `bitmap` in place. Must be
     *  called with an `ARGB_8888` bitmap (the format `rememberSpoilerBackdrop`
     *  allocates). Radius < 1 is a no-op on the native side too. */
    fun stackBlur(bitmap: Bitmap, radius: Int) {
        if (!available) return
        nativeStackBlur(bitmap, radius)
    }

    @JvmStatic
    private external fun nativeStackBlur(bitmap: Bitmap, radius: Int)
}
