// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#pragma once

namespace photoedit {

/**
 * Crop + rotate + flip transform descriptor. All in normalised image-space:
 * `(x, y, w, h)` is the rect inside the *source* image after rotation, before
 * any of the flip flags are applied. `rotation90` is in 90° increments
 * (0..3), `freeAngle` is the free rotation in degrees (-45..+45) applied
 * *before* the 90° step.
 */
struct CropParams {
    float x = 0.f, y = 0.f;       // top-left in [0,1]
    float w = 1.f, h = 1.f;       // size in [0,1]
    int   rotation90 = 0;         // 0/1/2/3
    float freeAngle  = 0.f;       // degrees in [-45, +45]
    bool  mirrorH    = false;
    bool  mirrorV    = false;

    bool isIdentity() const {
        return x == 0.f && y == 0.f && w == 1.f && h == 1.f &&
               rotation90 == 0 && freeAngle == 0.f &&
               !mirrorH && !mirrorV;
    }
};

} // namespace photoedit
