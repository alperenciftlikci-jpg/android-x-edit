// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// CPU-side video filters. We can't reach the photo editor's GL FilterChain from
// the video pipeline (different module, different EGL context lifecycle), and
// shipping a separate EGL context for each decoded frame would more-than-double
// per-frame cost. Instead we run a small subset of the same colour math on the
// CPU after RGBA conversion.
//
// The set is deliberately limited to operations that are O(pixel_count) with no
// neighbour reads: exposure, brightness, contrast, saturation, warmth.
// Filters that need a kernel (sharpen, grain, blur) need GPU; we omit them.
#pragma once

#include <cstdint>

namespace videoedit {

struct SimpleFilterParams {
    float exposure   = 0.f;     // [-2, 2] EV
    float brightness = 0.f;     // [-1, 1]
    float contrast   = 1.f;     // [0, 2]
    float saturation = 1.f;     // [0, 2]
    float warmth     = 0.f;     // [-1, 1]

    bool isIdentity() const {
        return exposure == 0.f && brightness == 0.f && contrast == 1.f &&
               saturation == 1.f && warmth == 0.f;
    }
};

/**
 * Apply the filter to an RGBA byte buffer in-place. Pixel layout: tightly packed
 * RGBA8888, `width * height * 4` bytes. Each pixel is processed independently —
 * trivially parallelisable, but at 1080p×30fps a single thread fits comfortably
 * in the encode budget.
 */
void applySimpleFilters(uint8_t* rgba, int width, int height,
                        const SimpleFilterParams& params);

} // namespace videoedit
