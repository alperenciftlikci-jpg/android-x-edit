// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Brush types + parameters. Each brush is rendered as a series of stamps
// (one fragment-shader draw call per sample point), with the brush type
// selecting the stamp shader and per-sample blend mode.
#pragma once

namespace photoedit {

enum class BrushType : int {
    Pen     = 0,    // hard-edge solid disc
    Marker  = 1,    // soft-edge translucent disc, builds up on overlap
    Neon    = 2,    // glow (large, soft, additive) + core (small, hard)
    Arrow   = 3,    // pen path + arrowhead at the final segment
    Eraser  = 4,    // sets dst.a = 0 within the stamp radius
};

struct BrushParams {
    BrushType type = BrushType::Pen;
    float r = 1.f, g = 1.f, b = 1.f, a = 1.f;  // colour, premultiplied
    float radiusPx = 8.f;                       // stamp radius in destination pixels
    float hardness = 0.6f;                      // [0, 1] — pen-only edge falloff
};

struct StrokePoint {
    float x = 0.f, y = 0.f;       // pixel coords, dst-space
    float pressure = 1.f;         // [0, 1] — multiplies radius for variable thickness
};

} // namespace photoedit
