// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "CropEngine.h"

#include "../core/Logger.h"

#include <algorithm>
#include <cmath>

namespace photoedit {

namespace {

constexpr const char* kVs = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// 3x3 uv transform applied per fragment. We use mat3 instead of vertex-stage
// transform so a single full-screen quad maps to whatever cropped sub-rect we
// want, with rotation and mirror baked into the sample location.
constexpr const char* kFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform mat3 u_uvXform;
in vec2 v_uv;
out vec4 fragColor;

void main() {
    vec3 src = u_uvXform * vec3(v_uv, 1.0);
    // Pixels outside the source — black & transparent. Inscribed-rect logic in
    // outputSize() is what guarantees we don't actually sample here, but the
    // clamp below is a defensive belt-and-suspenders against off-by-one rounding.
    if (src.x < 0.0 || src.x > 1.0 || src.y < 0.0 || src.y > 1.0) {
        fragColor = vec4(0.0);
        return;
    }
    fragColor = texture(u_tex, src.xy);
}
)";

// Inscribed-rectangle factor for a free-angle rotation (radians), given the
// aspect ratio of the *destination* (post-90° rotate) box. Returns a uniform
// scale `s` such that an axis-aligned `s × box` fits inside the rotated box.
//
// Reference: largest axis-aligned rectangle inside a rotated rectangle —
//   if w >= h: ratio = h / (w * |sin| + h * |cos|)
//   else      ratio = w / (w * |cos| + h * |sin|)
// Equivalent, symmetric form below picks the tighter of the two so it works
// regardless of orientation.
float inscribedScale(float angleRad, float aspectW, float aspectH) {
    const float c = std::fabs(std::cos(angleRad));
    const float s = std::fabs(std::sin(angleRad));
    const float wh = aspectW;
    const float hh = aspectH;
    const float sx = std::min(wh, hh) / (wh * s + hh * c);
    const float sy = std::min(wh, hh) / (wh * c + hh * s);
    return std::min(sx, sy);
}

} // namespace

CropEngine::~CropEngine() { release(); }

bool CropEngine::init() {
    if (!quad_.createQuad()) return false;
    if (!shader_.compile(kVs, kFs)) {
        PE_LOGE("CropEngine: shader compile failed");
        return false;
    }
    return true;
}

void CropEngine::release() {
    shader_.release();
    quad_.release();
}

void CropEngine::outputSize(const CropParams& p, int srcW, int srcH, int& outW, int& outH) const {
    if (p.isIdentity()) {
        outW = srcW;
        outH = srcH;
        return;
    }

    // 1. Crop rect dictates the base output size.
    int croppedW = std::max(1, static_cast<int>(p.w * srcW));
    int croppedH = std::max(1, static_cast<int>(p.h * srcH));

    // 2. 90° rotation swaps W/H.
    int rW = croppedW;
    int rH = croppedH;
    if (p.rotation90 % 2 != 0) std::swap(rW, rH);

    // 3. Free-angle: shrink to the largest inscribed rectangle so we never sample
    //    outside the source.
    if (std::fabs(p.freeAngle) > 1e-3f) {
        const float angleRad = p.freeAngle * 3.14159265358979f / 180.f;
        const float scale = inscribedScale(angleRad,
                                           static_cast<float>(rW),
                                           static_cast<float>(rH));
        rW = std::max(1, static_cast<int>(rW * scale));
        rH = std::max(1, static_cast<int>(rH * scale));
    }
    outW = rW;
    outH = rH;
}

void CropEngine::buildUvMatrix(const CropParams& p, float out[9]) {
    // Column-major mat3 layout (GLSL):
    //   m[0][0] m[0][1] m[0][2]   first column  (x basis vector)
    //   m[1][0] m[1][1] m[1][2]   second column (y basis vector)
    //   m[2][0] m[2][1] m[2][2]   third column  (translation)
    //
    // We construct the transform conceptually as:
    //   uv_src = T_origin · S_crop · R_quarter · R_free · M_mirror · T_-centre · uv
    // and multiply through by hand. (We don't pull in glm just for one 3x3.)

    // Start: shift origin to 0.5,0.5 so subsequent rotate/scale pivot from centre.
    float a = 1.f, b = 0.f;
    float c = 0.f, d = 1.f;
    float tx = -0.5f, ty = -0.5f;

    // Mirror — pre-rotate so users see "flip then rotate" semantics.
    if (p.mirrorH) { a = -a; c = -c; tx = -tx; }
    if (p.mirrorV) { b = -b; d = -d; ty = -ty; }

    // Free angle (degrees → radians). Counter-clockwise.
    const float ang = p.freeAngle * 3.14159265358979f / 180.f;
    const float ca = std::cos(ang);
    const float sa = std::sin(ang);
    const float a2 = ca * a - sa * b;
    const float b2 = sa * a + ca * b;
    const float c2 = ca * c - sa * d;
    const float d2 = sa * c + ca * d;
    const float tx2 = ca * tx - sa * ty;
    const float ty2 = sa * tx + ca * ty;
    a = a2; b = b2; c = c2; d = d2; tx = tx2; ty = ty2;

    // 90° rotation step.
    int q = ((p.rotation90 % 4) + 4) % 4;
    for (int i = 0; i < q; ++i) {
        // rotation by 90° CW: (x, y) → (y, -x)
        const float ax = c, bx = d, cx = -a, dx = -b;
        const float txq = ty, tyq = -tx;
        a = ax; b = bx; c = cx; d = dx; tx = txq; ty = tyq;
    }

    // Translate back from centre to (0.5, 0.5) origin in source space.
    tx += 0.5f;
    ty += 0.5f;

    // Now apply the crop rect: scale by (w, h) and shift by (x, y) in source uv.
    a *= p.w; b *= p.h;
    c *= p.w; d *= p.h;
    tx = tx * p.w + p.x;
    ty = ty * p.h + p.y;

    // Store column-major.
    out[0] = a;  out[1] = c;  out[2] = 0.f;
    out[3] = b;  out[4] = d;  out[5] = 0.f;
    out[6] = tx; out[7] = ty; out[8] = 1.f;
}

bool CropEngine::process(const Texture& source, const CropParams& params, Framebuffer& dst) {
    if (!isReady()) return false;
    dst.bind();
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);

    shader_.use();
    source.bind(GL_TEXTURE0);
    shader_.setInt("u_tex", 0);

    float uvXform[9];
    buildUvMatrix(params, uvXform);
    glUniformMatrix3fv(shader_.location("u_uvXform"), 1, GL_FALSE, uvXform);

    quad_.draw();
    Framebuffer::bindDefault();
    return true;
}

} // namespace photoedit
