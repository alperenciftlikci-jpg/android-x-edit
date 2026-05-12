// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Pre-blurred copy of the source texture, produced by a 2-pass separable
// Gaussian. The Telegram-style "blur brush" reveals this texture through a
// painted mask — by pre-computing the blur once per source upload, the mask
// pass is a cheap mix() instead of a per-frame blur.
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

    // Run the 2-pass separable Gaussian over `source`. Output lands in this
    // object's `texture()`. Idempotent — safe to re-run when the user changes
    // the source bitmap or wants a different blur strength.
    bool process(const Texture& source, float sigma);

    const Texture& texture() const { return verticalFbo_.texture(); }
    bool isValid() const { return ready_; }

private:
    bool compileShader();
    bool initFbos(int width, int height);

    bool ready_ = false;
    int width_ = 0, height_ = 0;
    Shader blurShader_;       // single shader, direction supplied by u_texelStep
    Mesh   quad_;
    Framebuffer horizontalFbo_;   // intermediate (post horizontal pass)
    Framebuffer verticalFbo_;     // final output
};

} // namespace photoedit
