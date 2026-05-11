// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// FBO with an attached color texture. Used as the destination for filter passes
// and as the ping-pong target in `FilterChain` (Phase 2).
#pragma once

#include "Texture.h"

namespace photoedit {

class Framebuffer {
public:
    Framebuffer() = default;
    ~Framebuffer();

    Framebuffer(const Framebuffer&) = delete;
    Framebuffer& operator=(const Framebuffer&) = delete;

    bool create(int width, int height);
    void release();

    void bind() const;
    static void bindDefault();

    GLuint id()       const { return fbo_; }
    Texture& texture()       { return texture_; }
    const Texture& texture() const { return texture_; }
    int width()       const { return texture_.width(); }
    int height()      const { return texture_.height(); }
    bool isValid()    const { return fbo_ != 0 && texture_.isValid(); }

private:
    GLuint fbo_ = 0;
    Texture texture_;
};

} // namespace photoedit
