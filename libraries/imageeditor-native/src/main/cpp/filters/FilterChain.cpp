// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "FilterChain.h"

#include "../core/Logger.h"
#include "Shaders.h"

#include <cmath>
#include <vector>

namespace photoedit {

namespace {

// Same vertex shader the passthrough renderer uses. Filters never modify the
// vertex stage, only the fragment colour math.
constexpr const char* kVertexSrc = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// Match the fragment shader's `u_tex` to texture unit 0.
constexpr int kInputUnit = 0;
constexpr int kLutUnit   = 1;

bool isClose(float a, float b, float tol = 1e-4f) {
    return std::fabs(a - b) <= tol;
}

} // namespace

FilterChain::~FilterChain() { release(); }

bool FilterChain::init(int width, int height) {
    width_ = width;
    height_ = height;
    if (!quad_.createQuad()) return false;
    if (!compileAll()) return false;
    if (!pingPong_[0].create(width, height)) return false;
    if (!pingPong_[1].create(width, height)) return false;
    ready_ = true;
    return true;
}

bool FilterChain::resize(int width, int height) {
    if (width == width_ && height == height_) return true;
    width_ = width;
    height_ = height;
    pingPong_[0].release();
    pingPong_[1].release();
    if (!pingPong_[0].create(width, height)) return false;
    if (!pingPong_[1].create(width, height)) return false;
    return true;
}

void FilterChain::release() {
    pingPong_[0].release();
    pingPong_[1].release();
    for (auto& s : shaders_) s.release();
    quad_.release();
    curvesLut_.release();
    ready_ = false;
}

bool FilterChain::compileAll() {
    using S = Stage;
    auto compile = [&](S stage, const char* fs) {
        return shaders_[static_cast<size_t>(stage)].compile(kVertexSrc, fs);
    };
    return compile(S::ToneBcs,            shaders::kFsToneBcs)
        && compile(S::Warmth,             shaders::kFsWarmth)
        && compile(S::Fade,               shaders::kFsFade)
        && compile(S::HighlightsShadows,  shaders::kFsHighlightsShadows)
        && compile(S::Vignette,           shaders::kFsVignette)
        && compile(S::Grain,              shaders::kFsGrain)
        && compile(S::Sharpen,            shaders::kFsSharpen)
        && compile(S::Tint,               shaders::kFsTint)
        && compile(S::Blur,               shaders::kFsBlur)
        && compile(S::Curves,             shaders::kFsCurves);
}

bool FilterChain::isStageActive(Stage stage, const FilterParams& p) const {
    switch (stage) {
        case Stage::ToneBcs:
            return !isClose(p.exposure, 0.f) || !isClose(p.brightness, 0.f) ||
                   !isClose(p.contrast, 1.f) || !isClose(p.saturation, 1.f);
        case Stage::Warmth:            return !isClose(p.warmth, 0.f);
        case Stage::Fade:              return !isClose(p.fade, 0.f);
        case Stage::HighlightsShadows: return !isClose(p.highlights, 0.f) || !isClose(p.shadows, 0.f);
        case Stage::Vignette:          return !isClose(p.vignette, 0.f);
        case Stage::Grain:             return !isClose(p.grain, 0.f);
        case Stage::Sharpen:           return !isClose(p.sharpen, 0.f);
        case Stage::Tint:              return p.tintShadows[3] > 1e-4f || p.tintHighlights[3] > 1e-4f;
        case Stage::Blur:              return p.blurType != 0 && p.blurStrength > 1e-4f;
        case Stage::Curves:            return !p.curves.isIdentity;
        case Stage::kCount: break;
    }
    return false;
}

void FilterChain::applyUniforms(Stage stage, Shader& s, const FilterParams& p) {
    s.setInt("u_tex", kInputUnit);
    switch (stage) {
        case Stage::ToneBcs:
            s.setFloat("u_exposure",   p.exposure);
            s.setFloat("u_brightness", p.brightness);
            s.setFloat("u_contrast",   p.contrast);
            s.setFloat("u_saturation", p.saturation);
            break;
        case Stage::Warmth:
            s.setFloat("u_amount", p.warmth);
            break;
        case Stage::Fade:
            s.setFloat("u_amount", p.fade);
            break;
        case Stage::HighlightsShadows:
            s.setFloat("u_highlights", p.highlights);
            s.setFloat("u_shadows",    p.shadows);
            break;
        case Stage::Vignette:
            s.setFloat("u_intensity", p.vignette);
            break;
        case Stage::Grain:
            s.setFloat("u_amount", p.grain);
            s.setFloat("u_seed",   static_cast<float>(frameCounter_ % 100000u));
            s.setVec2("u_resolution", static_cast<float>(width_), static_cast<float>(height_));
            break;
        case Stage::Sharpen:
            s.setFloat("u_amount", p.sharpen);
            s.setVec2("u_texelSize", 1.0f / width_, 1.0f / height_);
            break;
        case Stage::Tint:
            s.setVec4("u_tintShadows",
                      p.tintShadows[0], p.tintShadows[1], p.tintShadows[2], p.tintShadows[3]);
            s.setVec4("u_tintHighlights",
                      p.tintHighlights[0], p.tintHighlights[1], p.tintHighlights[2], p.tintHighlights[3]);
            break;
        case Stage::Blur:
            s.setInt  ("u_mode",         p.blurType);
            s.setVec2 ("u_centre",       p.blurCenterX, p.blurCenterY);
            s.setFloat("u_innerRadius",  p.blurInnerRadius);
            s.setFloat("u_outerRadius",  p.blurOuterRadius);
            s.setFloat("u_angle",        p.blurAngle);
            s.setFloat("u_strength",     p.blurStrength);
            s.setVec2 ("u_texelSize",    1.0f / width_, 1.0f / height_);
            break;
        case Stage::Curves:
            s.setInt("u_lut", kLutUnit);
            break;
        case Stage::kCount: break;
    }
}

void FilterChain::uploadCurvesLut(const FilterParams& p) {
    // 256x1 RGBA — pack r/g/b/luma into the 4 channels per texel. Re-uploaded
    // only when the lookup actually changes (cheap CPU compare via the dirty
    // bit set by the JNI layer when params land).
    std::vector<uint8_t> buf(256 * 4);
    for (int i = 0; i < 256; ++i) {
        buf[i * 4 + 0] = static_cast<uint8_t>(p.curves.r[i]    * 255.f);
        buf[i * 4 + 1] = static_cast<uint8_t>(p.curves.g[i]    * 255.f);
        buf[i * 4 + 2] = static_cast<uint8_t>(p.curves.b[i]    * 255.f);
        buf[i * 4 + 3] = static_cast<uint8_t>(p.curves.luma[i] * 255.f);
    }
    curvesLut_.create(256, 1, buf.data());
    curvesLutDirty_ = false;
}

const Texture& FilterChain::process(const Texture& source, const FilterParams& params) {
    if (!ready_) return source;

    // Build the active-stage list so we can correctly identify the *last*
    // stage and route its output to the final FBO.
    std::vector<Stage> active;
    active.reserve(static_cast<size_t>(Stage::kCount));
    for (int i = 0; i < static_cast<int>(Stage::kCount); ++i) {
        Stage s = static_cast<Stage>(i);
        if (isStageActive(s, params)) active.push_back(s);
    }
    if (active.empty()) return source;

    // Ensure the curves LUT is up-to-date before the curves stage might run.
    if (curvesLutDirty_ || !curvesLut_.isValid()) {
        uploadCurvesLut(params);
    }

    int srcIdx = 0;        // ping-pong index of the *next* input
    const Texture* input = &source;

    for (size_t i = 0; i < active.size(); ++i) {
        Stage stage = active[i];
        Framebuffer& dst = pingPong_[srcIdx ^ 1];
        dst.bind();
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);

        Shader& s = shaders_[static_cast<size_t>(stage)];
        s.use();
        applyUniforms(stage, s, params);

        input->bind(GL_TEXTURE0 + kInputUnit);
        if (stage == Stage::Curves) {
            curvesLut_.bind(GL_TEXTURE0 + kLutUnit);
        }
        quad_.draw();
        Framebuffer::bindDefault();

        // Output of this stage feeds the next stage as input.
        srcIdx ^= 1;
        input = &pingPong_[srcIdx].texture();
    }
    return *input;
}

} // namespace photoedit
