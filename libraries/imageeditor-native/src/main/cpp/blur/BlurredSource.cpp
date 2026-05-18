// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "BlurredSource.h"

#include <algorithm>

#include "../core/Logger.h"

namespace photoedit {

namespace {

// Standard NDC-quad VS — full-screen pass with passthrough UVs. Shared by the
// downsample shader and the blur shader.
constexpr const char* kSharedVs = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// Passthrough sampler used for the downsample pass. The viewport defines the
// output resolution; bilinear texture filtering (set in Texture::create) does
// the 4-tap-per-fragment averaging during the read. That's mild anti-aliasing
// for an 8x downsample — strictly speaking a box filter would be more correct
// — but the Gaussian pass that follows hides any leftover aliasing well below
// the threshold a user could notice, so the extra shader complexity isn't
// worth it.
constexpr const char* kDownsampleFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    fragColor = texture(u_tex, v_uv);
}
)";

// Separable Gaussian. `u_texelStep` selects direction:
//   horizontal pass: (1/dsW, 0)
//   vertical pass:   (0, 1/dsH)
// Runs at 1/8 resolution, so the 25-tap kernel (radius 12 in low-res pixels)
// covers ~96 full-res pixels — wide enough that the Gaussian tail decays
// naturally at the maximum supported sigma (10 in low-res space ≈ 80 in
// full-res space). Weights computed in-shader: cheaper than uploading a
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
    fullW_ = width;
    fullH_ = height;
    if (!quad_.createQuad()) {
        PE_LOGE("BlurredSource: quad creation failed");
        return false;
    }
    if (!compileShaders()) {
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
    fullW_ = width;
    fullH_ = height;
    return initFbos(width, height);
}

void BlurredSource::release() {
    downsampleFbo_.release();
    horizontalFbo_.release();
    verticalFbo_.release();
    downsampleShader_.release();
    blurShader_.release();
    quad_.release();
    ready_ = false;
    fullW_ = fullH_ = 0;
    dsW_ = dsH_ = 0;
}

bool BlurredSource::compileShaders() {
    if (!downsampleShader_.compile(kSharedVs, kDownsampleFs)) return false;
    if (!blurShader_.compile(kSharedVs, kBlurFs)) return false;
    return true;
}

bool BlurredSource::initFbos(int width, int height) {
    // Floor-divide and clamp to at least 1 — tiny source bitmaps (under 8 px
    // on a side) would otherwise produce a 0-sized FBO and fail creation.
    dsW_ = std::max(1, width  / kDownsampleFactor);
    dsH_ = std::max(1, height / kDownsampleFactor);
    if (!downsampleFbo_.create(dsW_, dsH_)) return false;
    if (!horizontalFbo_.create(dsW_, dsH_)) return false;
    if (!verticalFbo_.create(dsW_, dsH_))   return false;
    return true;
}

bool BlurredSource::process(const Texture& source, float sigma) {
    if (!ready_) return false;
    if (!source.isValid()) return false;

    glDisable(GL_BLEND);

    // Pass 0: downsample full-res source into the 1/8-res FBO via bilinear
    // sampling. This is the cheap step that makes the subsequent Gaussian
    // effectively 8x stronger per shader sample.
    downsampleShader_.use();
    downsampleFbo_.bind();
    glViewport(0, 0, dsW_, dsH_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    source.bind(GL_TEXTURE0);
    downsampleShader_.setInt("u_tex", 0);
    quad_.draw();

    // Pass 1: horizontal Gaussian at 1/8 res — downsampleFbo_ → horizontalFbo_.
    blurShader_.use();
    horizontalFbo_.bind();
    glViewport(0, 0, dsW_, dsH_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    downsampleFbo_.texture().bind(GL_TEXTURE0);
    blurShader_.setInt("u_tex", 0);
    blurShader_.setVec2("u_texelStep", 1.f / static_cast<float>(dsW_), 0.f);
    blurShader_.setFloat("u_sigma", sigma);
    quad_.draw();

    // Pass 2: vertical Gaussian at 1/8 res — horizontalFbo_ → verticalFbo_ (final).
    verticalFbo_.bind();
    glViewport(0, 0, dsW_, dsH_);
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    horizontalFbo_.texture().bind(GL_TEXTURE0);
    blurShader_.setInt("u_tex", 0);
    blurShader_.setVec2("u_texelStep", 0.f, 1.f / static_cast<float>(dsH_));
    blurShader_.setFloat("u_sigma", sigma);
    quad_.draw();

    Framebuffer::bindDefault();
    return true;
}

} // namespace photoedit
