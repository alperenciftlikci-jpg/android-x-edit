// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "BlurredSource.h"

#include "../core/Logger.h"

namespace photoedit {

namespace {

// Standard NDC-quad VS — full-screen pass with passthrough UVs.
constexpr const char* kBlurVs = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// Separable Gaussian. `u_texelStep` selects direction:
//   horizontal pass: (1/w, 0)
//   vertical pass:   (0, 1/h)
// 25-tap (radius 12) — wide enough for sigma up to ~6 without truncation
// artefacts. Weights computed in-shader: cheaper than uploading a 25-float
// uniform array each pass and visually identical at this radius.
constexpr const char* kBlurFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform vec2 u_texelStep;
uniform float u_sigma;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    const int kRadius = 12;
    float sigma = max(u_sigma, 0.0001);
    float twoSigmaSq = 2.0 * sigma * sigma;
    vec4 acc = vec4(0.0);
    float sum = 0.0;
    for (int i = -kRadius; i <= kRadius; ++i) {
        float fi = float(i);
        float w = exp(-fi * fi / twoSigmaSq);
        acc += texture(u_tex, v_uv + u_texelStep * fi) * w;
        sum += w;
    }
    fragColor = acc / sum;
}
)";

} // namespace

BlurredSource::~BlurredSource() { release(); }

bool BlurredSource::init(int width, int height) {
    width_ = width;
    height_ = height;
    if (!quad_.createQuad()) {
        PE_LOGE("BlurredSource: quad creation failed");
        return false;
    }
    if (!compileShader()) {
        PE_LOGE("BlurredSource: shader compile failed");
        return false;
    }
    if (!initFbos(width, height)) {
        PE_LOGE("BlurredSource: FBO init failed");
        return false;
    }
    ready_ = true;
    return true;
}

bool BlurredSource::resize(int width, int height) {
    width_ = width;
    height_ = height;
    return initFbos(width, height);
}

void BlurredSource::release() {
    horizontalFbo_.release();
    verticalFbo_.release();
    blurShader_.release();
    quad_.release();
    ready_ = false;
    width_ = height_ = 0;
}

bool BlurredSource::compileShader() {
    return blurShader_.compile(kBlurVs, kBlurFs);
}

bool BlurredSource::initFbos(int width, int height) {
    if (!horizontalFbo_.create(width, height)) return false;
    if (!verticalFbo_.create(width, height)) return false;
    return true;
}

bool BlurredSource::process(const Texture& source, float sigma) {
    if (!ready_) return false;
    if (!source.isValid()) return false;

    blurShader_.use();
    glDisable(GL_BLEND);

    // Pass 1: horizontal — source → horizontalFbo_.
    horizontalFbo_.bind();
    glViewport(0, 0, width_, height_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    source.bind(GL_TEXTURE0);
    blurShader_.setInt("u_tex", 0);
    blurShader_.setVec2("u_texelStep", 1.f / static_cast<float>(width_), 0.f);
    blurShader_.setFloat("u_sigma", sigma);
    quad_.draw();

    // Pass 2: vertical — horizontalFbo_ → verticalFbo_ (final).
    verticalFbo_.bind();
    glViewport(0, 0, width_, height_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    horizontalFbo_.texture().bind(GL_TEXTURE0);
    blurShader_.setInt("u_tex", 0);
    blurShader_.setVec2("u_texelStep", 0.f, 1.f / static_cast<float>(height_));
    blurShader_.setFloat("u_sigma", sigma);
    quad_.draw();

    Framebuffer::bindDefault();
    return true;
}

} // namespace photoedit
