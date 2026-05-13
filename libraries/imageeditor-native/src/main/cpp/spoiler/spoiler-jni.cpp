// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// JNI binding for the spoiler native pipeline. Exposes [StackBlur] to Kotlin's
// `SpoilerJni` object — see the Kotlin side for the loader/wrapper.
#include <android/bitmap.h>
#include <cstdint>
#include <jni.h>

#include "StackBlur.h"
#include "../core/Logger.h"

extern "C" {

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_ui_components_SpoilerJni_nativeStackBlur(
        JNIEnv* env, jclass /*clazz*/, jobject bitmap, jint radius) {
    if (bitmap == nullptr) return;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        PE_LOGE("spoiler-jni: AndroidBitmap_getInfo failed");
        return;
    }
    // We only handle the standard ARGB_8888 format — the layout
    // `rememberSpoilerBackdrop` allocates. RGB_565 would need a different unpack
    // path; we don't bother since the spoiler backdrop is always ARGB_8888.
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        PE_LOGE("spoiler-jni: unsupported bitmap format %d", info.format);
        return;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || pixels == nullptr) {
        PE_LOGE("spoiler-jni: lockPixels failed");
        return;
    }

    photoedit::spoiler::stackBlur(
            static_cast<uint32_t*>(pixels),
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<int>(radius));

    AndroidBitmap_unlockPixels(env, bitmap);
}

} // extern "C"
