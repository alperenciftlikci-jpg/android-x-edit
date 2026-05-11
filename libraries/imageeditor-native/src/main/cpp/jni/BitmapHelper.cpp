// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "BitmapHelper.h"

#include "../core/Logger.h"

namespace photoedit {

LockedBitmap::LockedBitmap(JNIEnv* env, jobject bitmap)
    : env_(env), bitmap_(bitmap) {
    if (AndroidBitmap_getInfo(env_, bitmap_, &info_) < 0) {
        PE_LOGE("AndroidBitmap_getInfo failed");
        return;
    }
    if (info_.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        PE_LOGE("Bitmap is not RGBA_8888 (format=%d)", info_.format);
        return;
    }
    if (AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) < 0) {
        PE_LOGE("AndroidBitmap_lockPixels failed");
        pixels_ = nullptr;
    }
}

LockedBitmap::~LockedBitmap() {
    if (pixels_ != nullptr) {
        AndroidBitmap_unlockPixels(env_, bitmap_);
    }
}

} // namespace photoedit
