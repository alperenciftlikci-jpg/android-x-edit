// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// FFmpeg-based stream-copy trim, equivalent to `ffmpeg -ss START -to END -i IN -c copy
// OUT`. No decode, no encode — packets read from libavformat are rewritten as-is into
// a new MP4 container, with timestamps rebased and per-stream timebase rescaled.
//
// Advantage over the Java MP4Builder path (Mp4TrimEngine):
//   - FFmpeg parses MP4 directly, so we always get container-native samples (AVCC for
//     H.264 in MP4). Bypasses the Annex-B-vs-AVCC emulator quirk that plagues the
//     Java MediaExtractor path on Android 16 preview.
//   - Single C++ loop with no JNI bouncing per packet.
//
// Caveat: Telegram's prebuilt FFmpeg covers armeabi-v7a + x86_64 only (arm64 disabled
// due to text relocations in the inline ASM). Real arm64 phones won't have this lib;
// callers must fall back to the Java path there.
#pragma once

#include <cstdint>
#include <functional>
#include <string>

namespace videoedit {

struct StreamCopyTrimResult {
    bool        success     = false;
    std::string error;
    int64_t     bytesWritten = 0;
    int         packetsWritten = 0;
    int64_t     firstPtsUs  = 0;
    int64_t     lastPtsUs   = 0;
};

// Progress callback: fraction is 0..1. Called from the loop thread, not throttled —
// caller should throttle on the JNI side to avoid flooding Java listeners.
using TrimProgressCallback = std::function<void(float fraction)>;

// Stream-copy trim. inputPath and outputPath are filesystem paths (FFmpeg also accepts
// /proc/self/fd/N for pre-opened FDs). startUs/endUs in microseconds; pass endUs<=0 to
// trim until EOF.
StreamCopyTrimResult streamCopyTrim(
    const char* inputPath,
    const char* outputPath,
    int64_t startUs,
    int64_t endUs,
    const TrimProgressCallback& progress);

} // namespace videoedit
