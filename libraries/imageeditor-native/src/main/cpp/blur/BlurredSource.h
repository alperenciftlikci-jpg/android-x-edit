// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Pre-blurred copy of the source texture, produced by a 2-pass separable
// Gaussian. The Telegram-style "blur brush" reveals this texture through a
// painted mask — by pre-computing the blur once per source upload, the mask
// pass is a cheap mix() instead of a per-frame blur.
//
// The blur runs at 1/8 resolution. Reducing resolution before blurring
// multiplies effective blur radius by the downsample factor for the same
// shader cost — a 25-tap kernel at sigma 6 on a 1/8 texture is equivalent
// to a ~200-tap kernel at sigma 48 on the full-res image. That's what makes
// strong "censor" strengths actually erase legibility instead of just
// softening edges (the prior full-res path topped out around an effective
// box blur because the 25-tap kernel truncated the Gaussian tail).
#pragma once

#include "../core/Framebuffer.h"
#include "../core/Mesh.h"
#include "../core/Shader.h"
#include "../core/Texture.h"

namespace photoedit {

class BlurredSource {
public:
    BlurredSource() = default;
    ~BlurredSource();

    bool init(int width, int height);
    void release();
    bool resize(int width, int height);

    // Run downsample + 2-pass separable Gaussian over `source`. Output lands
    // in this object's `texture()` at 1/8 resolution; consumers sample it with
    // full-res UVs and let bilinear filtering handle the upscale (which adds a
    // little extra smoothing on top of the Gaussian, helping hide any
    // downsample aliasing). Idempotent — safe to re-run when the source
    // bitmap or blur strength changes.
    bool process(const Texture& source, float sigma);

    const Texture& texture() const { return verticalFbo_.texture(); }
    bool isValid() const { return ready_; }

private:
    bool compileShaders();
    bool initFbos(int width, int height);

    // Aggressive: 8x means 64x fewer pixels to blur, and the equivalent
    // full-res sigma is 8x what the shader sees — so max-strength sigma 10
    // becomes an effective sigma of 80, well past the point where text on
    // the underlying image is recoverable.
    static constexpr int kDownsampleFactor = 8;

    bool ready_ = false;
    int fullW_ = 0, fullH_ = 0;
    int dsW_ = 0,   dsH_ = 0;
    Shader downsampleShader_; // passthrough sampler; bilinear filter does the work
    Shader blurShader_;       // single shader, direction supplied by u_texelStep
    Mesh   quad_;
    Framebuffer downsampleFbo_;   // full-res source → 1/8 res copy
    Framebuffer horizontalFbo_;   // intermediate (post horizontal pass) @ 1/8 res
    Framebuffer verticalFbo_;     // final output @ 1/8 res
};

} // namespace photoedit
