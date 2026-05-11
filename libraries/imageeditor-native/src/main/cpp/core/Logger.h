// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Thin wrapper around __android_log_print. Uses the same tag for the whole
// library so logcat filtering on `photoedit` catches every line we emit.
#pragma once

#include <android/log.h>

namespace photoedit {

constexpr const char* kLogTag = "photoedit";

#define PE_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  ::photoedit::kLogTag, __VA_ARGS__)
#define PE_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  ::photoedit::kLogTag, __VA_ARGS__)
#define PE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ::photoedit::kLogTag, __VA_ARGS__)

#ifndef NDEBUG
#define PE_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, ::photoedit::kLogTag, __VA_ARGS__)
#else
#define PE_LOGD(...) do {} while (0)
#endif

// Check the GL error queue and log if non-zero. Debug builds only; in release
// the call expands to nothing so we avoid the round-trip cost on the hot path.
void checkGlError(const char* op);

} // namespace photoedit
