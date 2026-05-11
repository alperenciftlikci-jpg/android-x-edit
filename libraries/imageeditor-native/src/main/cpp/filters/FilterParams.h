// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Photo-filter parameter struct. Default-constructed value is the identity (no
// filter applied) — `FilterChain` skips any filter whose params are at default,
// so a fresh source bitmap renders through an empty pipeline at zero cost.
#pragma once

#include <array>

namespace photoedit {

// 256-entry RGB curves LUT. r/g/b/luma values in [0,1]; default = linear ramp.
struct CurveLut {
    std::array<float, 256> r;
    std::array<float, 256> g;
    std::array<float, 256> b;
    std::array<float, 256> luma;
    bool isIdentity = true;

    CurveLut() {
        for (int i = 0; i < 256; ++i) {
            float t = i / 255.0f;
            r[i] = g[i] = b[i] = luma[i] = t;
        }
    }
};

struct FilterParams {
    // Tone
    float exposure   = 0.f;     // [-2, 2] EV
    float brightness = 0.f;     // [-1, 1]
    float contrast   = 1.f;     // [0, 2], 1.0 = identity
    float saturation = 1.f;     // [0, 2], 1.0 = identity

    // Color
    float warmth     = 0.f;     // [-1, 1]  cool ↔ warm
    float fade       = 0.f;     // [0, 1]   matte film fade
    float highlights = 0.f;     // [-1, 1]
    float shadows    = 0.f;     // [-1, 1]

    // Effects
    float vignette   = 0.f;     // [0, 1]
    float grain      = 0.f;     // [0, 1]
    float sharpen    = 0.f;     // [0, 1]

    // Tint (RGB color, intensity is the alpha channel)
    float tintShadows[4]    = {0, 0, 0, 0};
    float tintHighlights[4] = {0, 0, 0, 0};

    // Curves LUT (256x4 RGBA texture uploaded by FilterChain)
    CurveLut curves;

    // Selective focus blur — Telegram-style. Two modes:
    //   blurType = 0 → off
    //   blurType = 1 → radial (circular focal area centred on `blurCenterX/Y` with radius
    //                  `blurInnerRadius`/`blurOuterRadius`; outside the outer ring fully
    //                  blurred, inside the inner ring sharp, smoothstep between).
    //   blurType = 2 → linear (gradient blur orthogonal to the angle `blurAngle`; pixels
    //                  within `blurInnerRadius` of the centre line are sharp, beyond
    //                  `blurOuterRadius` fully blurred).
    int   blurType         = 0;
    float blurCenterX      = 0.5f;     // [0, 1] image-space
    float blurCenterY      = 0.5f;
    float blurInnerRadius  = 0.15f;    // sharp area
    float blurOuterRadius  = 0.35f;    // full-blur boundary
    float blurAngle        = 0.f;      // radians (linear blur only)
    float blurStrength     = 0.6f;     // [0, 1] — multiplier on the kernel size
};

} // namespace photoedit
