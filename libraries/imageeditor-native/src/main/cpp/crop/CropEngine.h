// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Builds a uv transform that the crop fragment shader applies. The crop is a
// pure-GPU operation: vertex shader passes through NDC, the fragment shader
// samples the input texture using a 3x3 transform applied to v_uv, and the
// output FBO size becomes the cropped image's pixel size.
//
// Why a uv transform and not a vertex transform: keeping the quad NDC-fixed
// means we draw exactly the output rectangle. If we transformed vertices,
// we'd render an arbitrarily-sized rotated quad and have to size the output
// FBO around its bounding box — more code paths, more aliasing on rotation.
#pragma once

#include "../core/Framebuffer.h"
#include "../core/Mesh.h"
#include "../core/Shader.h"
#include "../core/Texture.h"
#include "CropParams.h"

namespace photoedit {

class CropEngine {
public:
    CropEngine() = default;
    ~CropEngine();

    bool init();
    void release();

    // Compute the output (FBO) dimensions implied by `params` for a given source size.
    // Crop alone scales the output; rotation alone preserves area; free-angle rotation
    // shrinks the output by the inscribed-rect factor so we don't sample empty pixels.
    void outputSize(const CropParams& p, int srcW, int srcH, int& outW, int& outH) const;

    // Render `source` through the crop transform into `dst`. `dst` must be sized to
    // [outputSize](). Caller is responsible for allocating it.
    bool process(const Texture& source, const CropParams& params, Framebuffer& dst);

    bool isReady() const { return shader_.isValid() && quad_.isValid(); }

private:
    Shader shader_;
    Mesh   quad_;

    // Build the 3x3 uv-space matrix the fragment shader applies. Composed as:
    //   uv → centre → mirror → free-rotate → 90°-rotate → translate to crop origin → scale to crop size
    // The matrix is column-major to match GLSL's `mat3` constructor.
    static void buildUvMatrix(const CropParams& p, float out[9]);
};

} // namespace photoedit
