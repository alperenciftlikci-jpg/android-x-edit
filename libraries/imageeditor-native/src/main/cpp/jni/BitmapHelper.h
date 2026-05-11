// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// AndroidBitmap_lockPixels wrapper — RAII for lock/unlock so we never leak a
// locked bitmap if a JNI handler returns early on error.
#pragma once

#include <android/bitmap.h>
#include <jni.h>
#include <cstdint>

namespace photoedit {

class LockedBitmap {
public:
    LockedBitmap(JNIEnv* env, jobject bitmap);
    ~LockedBitmap();

    LockedBitmap(const LockedBitmap&) = delete;
    LockedBitmap& operator=(const LockedBitmap&) = delete;

    // True only if the bitmap is RGBA_8888 and lockPixels succeeded.
    bool   isValid() const { return pixels_ != nullptr; }
    uint8_t* pixels() const { return static_cast<uint8_t*>(pixels_); }
    int    width()   const { return static_cast<int>(info_.width); }
    int    height()  const { return static_cast<int>(info_.height); }

private:
    JNIEnv*           env_;
    jobject           bitmap_;
    AndroidBitmapInfo info_{};
    void*             pixels_ = nullptr;
};

} // namespace photoedit
