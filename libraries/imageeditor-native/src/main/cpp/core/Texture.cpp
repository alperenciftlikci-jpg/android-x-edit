// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "Texture.h"

#include "Logger.h"

namespace photoedit {

Texture::~Texture() {
    release();
}

Texture::Texture(Texture&& other) noexcept
    : id_(other.id_), width_(other.width_), height_(other.height_) {
    other.id_ = 0;
    other.width_ = 0;
    other.height_ = 0;
}

Texture& Texture::operator=(Texture&& other) noexcept {
    if (this != &other) {
        release();
        id_ = other.id_;
        width_ = other.width_;
        height_ = other.height_;
        other.id_ = 0;
        other.width_ = 0;
        other.height_ = 0;
    }
    return *this;
}

bool Texture::create(int width, int height, const uint8_t* pixels) {
    release();
    glGenTextures(1, &id_);
    if (id_ == 0) {
        PE_LOGE("glGenTextures returned 0");
        return false;
    }
    glBindTexture(GL_TEXTURE_2D, id_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    checkGlError("Texture::create");
    width_ = width;
    height_ = height;
    return true;
}

bool Texture::createEmpty(int width, int height) {
    return create(width, height, nullptr);
}

void Texture::release() {
    if (id_ != 0) {
        glDeleteTextures(1, &id_);
        id_ = 0;
        width_ = 0;
        height_ = 0;
    }
}

void Texture::bind(GLenum unit) const {
    glActiveTexture(unit);
    glBindTexture(GL_TEXTURE_2D, id_);
}

} // namespace photoedit
