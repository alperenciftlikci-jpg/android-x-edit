// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Logging shim. Same shape as the photoedit Logger so anyone reading both
// libraries' code finds the macros where they expect them.
#pragma once

#include <android/log.h>

namespace videoedit {

constexpr const char* kLogTag = "videoedit";

#define VE_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  ::videoedit::kLogTag, __VA_ARGS__)
#define VE_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  ::videoedit::kLogTag, __VA_ARGS__)
#define VE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ::videoedit::kLogTag, __VA_ARGS__)

#ifndef NDEBUG
#define VE_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, ::videoedit::kLogTag, __VA_ARGS__)
#else
#define VE_LOGD(...) do {} while (0)
#endif

} // namespace videoedit
