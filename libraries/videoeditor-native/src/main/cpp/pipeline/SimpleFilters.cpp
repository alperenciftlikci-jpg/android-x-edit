// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "SimpleFilters.h"

#include <algorithm>
#include <cmath>

namespace videoedit {

namespace {

// Same luma weights the photo editor's tone shader uses — keeps the perceived
// "saturation" slider response identical between photo and video on the same
// source.
constexpr float LUMA_R = 0.2126f;
constexpr float LUMA_G = 0.7152f;
constexpr float LUMA_B = 0.0722f;

inline uint8_t clampByte(float f) {
    if (f <= 0.f) return 0;
    if (f >= 255.f) return 255;
    return static_cast<uint8_t>(f);
}

} // namespace

void applySimpleFilters(uint8_t* rgba, int width, int height,
                        const SimpleFilterParams& p) {
    if (rgba == nullptr || width <= 0 || height <= 0) return;
    if (p.isIdentity()) return;

    const float expoMul = std::pow(2.0f, p.exposure);
    const float bright  = p.brightness * 255.f;
    const float contrast = p.contrast;
    const float sat = p.saturation;
    const float warm = p.warmth * 0.15f * 255.f;

    const int pixelCount = width * height;
    uint8_t* px = rgba;
    for (int i = 0; i < pixelCount; ++i, px += 4) {
        float r = px[0] * expoMul + bright;
        float g = px[1] * expoMul + bright;
        float b = px[2] * expoMul + bright;

        // Contrast around 0.5 (i.e. 127.5).
        r = (r - 127.5f) * contrast + 127.5f;
        g = (g - 127.5f) * contrast + 127.5f;
        b = (b - 127.5f) * contrast + 127.5f;

        // Saturation: blend toward greyscale luma.
        const float luma = LUMA_R * r + LUMA_G * g + LUMA_B * b;
        r = luma + (r - luma) * sat;
        g = luma + (g - luma) * sat;
        b = luma + (b - luma) * sat;

        // Warmth: push red, pull blue.
        r += warm;
        b -= warm;

        px[0] = clampByte(r);
        px[1] = clampByte(g);
        px[2] = clampByte(b);
        // px[3] (alpha) untouched.
    }
}

} // namespace videoedit
