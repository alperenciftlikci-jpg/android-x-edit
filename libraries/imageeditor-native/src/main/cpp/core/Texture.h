// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// RAII wrapper around a 2D GL texture. Move-only — copying a Texture would mean
// two C++ objects owning the same `glDeleteTextures` slot.
#pragma once

#include <GLES3/gl3.h>
#include <cstdint>

namespace photoedit {

class Texture {
public:
    Texture() = default;
    ~Texture();

    Texture(const Texture&) = delete;
    Texture& operator=(const Texture&) = delete;

    Texture(Texture&& other) noexcept;
    Texture& operator=(Texture&& other) noexcept;

    // Allocate an RGBA8 texture and upload `pixels` if non-null. `pixels` is
    // assumed to be tightly-packed RGBA bytes (Android Bitmap RGBA_8888 format).
    bool create(int width, int height, const uint8_t* pixels = nullptr);

    // Allocate without uploading (used for FBO color attachments).
    bool createEmpty(int width, int height);

    void release();

    void bind(GLenum unit = GL_TEXTURE0) const;

    GLuint id()     const { return id_; }
    int    width()  const { return width_; }
    int    height() const { return height_; }
    bool   isValid() const { return id_ != 0; }

private:
    GLuint id_     = 0;
    int    width_  = 0;
    int    height_ = 0;
};

} // namespace photoedit
