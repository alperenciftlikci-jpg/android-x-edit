// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "PaintEngine.h"

#include "../core/Logger.h"

#include <algorithm>
#include <cmath>

namespace photoedit {

namespace {

// Vertex shader for stamps: takes a unit-quad and transforms it to a screen-
// space disc centred at `u_centre` with radius `u_radius`. We pass v_local
// (the original [-1, 1] coords) to the fragment shader for distance math.
constexpr const char* kStampVs = R"(#version 300 es
layout(location = 0) in vec2 a_pos;       // -1..1 quad
uniform vec2 u_centre;                    // pixel coords on the paint layer
uniform float u_radius;                   // stamp radius in pixels
uniform vec2 u_layerSize;                 // pixels
out vec2 v_local;
void main() {
    v_local = a_pos;
    // Stamp position in pixel space, then to NDC for the paint layer's viewport.
    vec2 px  = u_centre + a_pos * u_radius;
    vec2 ndc = (px / u_layerSize) * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
)";

// Pen / eraser stamp: hard disc with a tunable falloff edge. The fragment is
// either drawn at full colour alpha or, in eraser mode, the destination's
// alpha is multiplied by (1 - mask) via the blend func — see beginStroke.
constexpr const char* kPenFs = R"(#version 300 es
precision highp float;
uniform vec4 u_color;
uniform float u_hardness;     // 0..1, 1 = hard edge
in vec2 v_local;
out vec4 fragColor;
void main() {
    float r = length(v_local);
    float edge = mix(0.6, 1.0, u_hardness);
    float a = 1.0 - smoothstep(edge, 1.0, r);
    if (a <= 0.0) discard;
    fragColor = vec4(u_color.rgb * u_color.a * a, u_color.a * a);
}
)";

// Marker stamp: soft disc with a strong centre, low alpha so overlapping
// stamps build up. Same vertex shader.
constexpr const char* kMarkerFs = R"(#version 300 es
precision highp float;
uniform vec4 u_color;
in vec2 v_local;
out vec4 fragColor;
void main() {
    float r = length(v_local);
    float a = (1.0 - smoothstep(0.0, 1.0, r)) * 0.55;
    if (a <= 0.0) discard;
    fragColor = vec4(u_color.rgb * a, a);
}
)";

// Neon: a wide soft halo + a tighter core, both additive. Two passes per stamp
// (the engine emits two draw calls, switching radius+alpha between them).
constexpr const char* kNeonFs = R"(#version 300 es
precision highp float;
uniform vec4 u_color;
uniform float u_corePass;     // 0 = halo, 1 = core
in vec2 v_local;
out vec4 fragColor;
void main() {
    float r = length(v_local);
    float halo = exp(-r * r * 3.0) * 0.55;
    float core = (1.0 - smoothstep(0.0, 0.5, r));
    float a = mix(halo, core, u_corePass);
    if (a <= 0.0) discard;
    fragColor = vec4(u_color.rgb * a, a);
}
)";

// Catmull-Rom interpolation between p1 and p2, given neighbours p0 and p3.
// Returns one interpolated point at parameter t ∈ [0, 1].
StrokePoint catmullRom(const StrokePoint& p0, const StrokePoint& p1,
                       const StrokePoint& p2, const StrokePoint& p3, float t) {
    const float t2 = t * t;
    const float t3 = t2 * t;
    StrokePoint out;
    auto interp = [&](float a, float b, float c, float d) {
        return 0.5f * ((2.f * b) +
                       (-a + c) * t +
                       (2.f * a - 5.f * b + 4.f * c - d) * t2 +
                       (-a + 3.f * b - 3.f * c + d) * t3);
    };
    out.x        = interp(p0.x,        p1.x,        p2.x,        p3.x);
    out.y        = interp(p0.y,        p1.y,        p2.y,        p3.y);
    out.pressure = interp(p0.pressure, p1.pressure, p2.pressure, p3.pressure);
    return out;
}

} // namespace

PaintEngine::~PaintEngine() { release(); }

bool PaintEngine::init(int width, int height) {
    width_ = width;
    height_ = height;
    if (!stampQuad_.createQuad()) return false;
    if (!compileShaders()) return false;
    if (!initFbos(width, height)) return false;
    ready_ = true;
    return true;
}

bool PaintEngine::resize(int width, int height) {
    width_ = width;
    height_ = height;
    return initFbos(width, height);
}

void PaintEngine::release() {
    paintFbo_.release();
    scratchFbo_.release();
    blurMaskFbo_.release();
    penShader_.release();
    markerShader_.release();
    neonShader_.release();
    stampQuad_.release();
    undoableSnapshots_.clear();
    redoableSnapshots_.clear();
    rawPoints_.clear();
    densified_.clear();
    inStroke_ = false;
    ready_ = false;
}

bool PaintEngine::compileShaders() {
    return penShader_.compile(kStampVs, kPenFs)
        && markerShader_.compile(kStampVs, kMarkerFs)
        && neonShader_.compile(kStampVs, kNeonFs);
}

bool PaintEngine::initFbos(int width, int height) {
    if (!paintFbo_.create(width, height)) return false;
    if (!scratchFbo_.create(width, height)) return false;
    if (!blurMaskFbo_.create(width, height)) return false;
    paintFbo_.bind();
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    blurMaskFbo_.bind();
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    Framebuffer::bindDefault();
    return true;
}

void PaintEngine::beginStroke(const BrushParams& brush, const StrokePoint& p) {
    if (!ready_) return;
    currentBrush_ = brush;
    rawPoints_.clear();
    rawPoints_.push_back(p);
    inStroke_ = true;
    redoableSnapshots_.clear();   // any new stroke invalidates the redo stack
    lastSent_ = p;
}

void PaintEngine::extendStroke(const StrokePoint& p) {
    if (!ready_ || !inStroke_) return;

    // Skip duplicates so a stationary finger doesn't pile up identical samples.
    const float dx = p.x - lastSent_.x;
    const float dy = p.y - lastSent_.y;
    if (dx * dx + dy * dy < 0.25f) return;

    rawPoints_.push_back(p);
    lastSent_ = p;

    // Densify the *segment that just became complete* — between the second-
    // last and last raw points, with the prior pair as the Catmull-Rom
    // shoulders. We need at least 4 raw points before we can do this; before
    // that we still draw a single stamp at the most recent point.
    densified_.clear();
    const size_t n = rawPoints_.size();
    if (n >= 4) {
        densify(rawPoints_[n - 3], rawPoints_[n - 2]);
    } else {
        densified_.push_back(p);
    }
    renderStamps();
}

void PaintEngine::endStroke() {
    if (!ready_ || !inStroke_) return;

    // Densify the trailing segment that the extend loop didn't cover yet. Use
    // the last raw points; the implicit ghost neighbour outside the stroke is
    // a mirror of the second-to-last point, which keeps the curve stable at
    // the endpoint.
    const size_t n = rawPoints_.size();
    densified_.clear();
    if (n >= 2) {
        StrokePoint p0 = (n >= 3) ? rawPoints_[n - 3] : rawPoints_[0];
        StrokePoint p1 = rawPoints_[n - 2];
        StrokePoint p2 = rawPoints_[n - 1];
        // Mirror p0 across p2 to produce a synthetic p3.
        StrokePoint p3{};
        p3.x = 2 * p2.x - p1.x;
        p3.y = 2 * p2.y - p1.y;
        p3.pressure = p2.pressure;
        // Walk t over [0..1] in screen-space to ~1 px steps.
        const float dx = p2.x - p1.x;
        const float dy = p2.y - p1.y;
        const float dist = std::sqrt(dx * dx + dy * dy);
        const int steps = std::max(1, static_cast<int>(dist));
        for (int i = 1; i <= steps; ++i) {
            const float t = static_cast<float>(i) / steps;
            densified_.push_back(catmullRom(p0, p1, p2, p3, t));
        }
    }
    renderStamps();

    // Arrow: append a head cap as a short pair of stamps angled off the last
    // segment. Skipped for short strokes (< 2 raw points) where the direction
    // would be ambiguous.
    if (currentBrush_.type == BrushType::Arrow && rawPoints_.size() >= 2) {
        const auto& last = rawPoints_.back();
        const auto& prev = rawPoints_[rawPoints_.size() - 2];
        const float dx = last.x - prev.x;
        const float dy = last.y - prev.y;
        const float len = std::sqrt(dx * dx + dy * dy);
        if (len > 1e-3f) {
            const float ux = dx / len, uy = dy / len;
            const float headLen = currentBrush_.radiusPx * 6.f;
            // Two segments at ±30° to the stroke direction.
            const float c30 = 0.866025f, s30 = 0.5f;
            for (float sgn : {-1.f, 1.f}) {
                const float rx = ux * c30 - sgn * uy * s30;
                const float ry = uy * c30 + sgn * ux * s30;
                densified_.clear();
                StrokePoint a = last;
                StrokePoint b = last;
                b.x = last.x - rx * headLen;
                b.y = last.y - ry * headLen;
                const int steps = std::max(1, static_cast<int>(headLen));
                for (int i = 0; i <= steps; ++i) {
                    StrokePoint p;
                    const float t = static_cast<float>(i) / steps;
                    p.x = a.x * (1 - t) + b.x * t;
                    p.y = a.y * (1 - t) + b.y * t;
                    p.pressure = 1.f;
                    densified_.push_back(p);
                }
                renderStamps();
            }
        }
    }

    // Paint strokes (Pen / Marker / Neon / Arrow / Eraser) push to the paint
    // undo stack here. BlurBrush is handled by the Renderer's bake step
    // (`Renderer::commitActiveBlurStroke`) right after this returns — it
    // snapshots the committed blur layer and clears the mask, so PaintEngine
    // doesn't need to touch any blur state.
    if (currentBrush_.type != BrushType::BlurBrush) {
        pushSnapshot(paintFbo_, undoableSnapshots_, /*capToMax=*/true);
        redoableSnapshots_.clear();
    }
    inStroke_ = false;
    rawPoints_.clear();
    densified_.clear();
}

void PaintEngine::densify(const StrokePoint& p0, const StrokePoint& p1) {
    // Walk from p0 to p1 in roughly 1 px steps. We use the previous two raw
    // points as the Catmull-Rom shoulders.
    const size_t n = rawPoints_.size();
    StrokePoint pa = (n >= 4) ? rawPoints_[n - 4] : p0;
    StrokePoint pb = p0;
    StrokePoint pc = p1;
    StrokePoint pd = (n >= 1) ? rawPoints_[n - 1] : p1;

    const float dx = pc.x - pb.x;
    const float dy = pc.y - pb.y;
    const float dist = std::sqrt(dx * dx + dy * dy);
    const int steps = std::max(1, static_cast<int>(dist));
    for (int i = 0; i <= steps; ++i) {
        const float t = static_cast<float>(i) / steps;
        densified_.push_back(catmullRom(pa, pb, pc, pd, t));
    }
}

void PaintEngine::renderStamps() {
    if (densified_.empty()) return;

    // BlurBrush has its own destination FBO — it doesn't write colour into the
    // paint layer at all, it just builds up an alpha mask the renderer's reveal
    // pass samples. Everything else (Pen, Marker, Neon, Arrow, Eraser) targets
    // the regular paint layer.
    const bool isBlur = currentBrush_.type == BrushType::BlurBrush;
    if (isBlur) {
        blurMaskFbo_.bind();
    } else {
        paintFbo_.bind();
    }
    glEnable(GL_BLEND);
    if (currentBrush_.type == BrushType::Eraser) {
        // Standard "destination-out" against premultiplied paint: result = dst * (1 - src.a).
        // Applied to BOTH RGB and A, otherwise marker / neon strokes (which use premultiplied
        // RGB) leave a colour ghost when only alpha is faded — the compose pass adds o.rgb on
        // top of the base, so non-zero RGB with alpha=0 still shows up.
        glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    } else if (currentBrush_.type == BrushType::Neon) {
        // Additive — halos overlap to brighten.
        glBlendFunc(GL_ONE, GL_ONE);
    } else {
        // Standard premultiplied src-over. BlurBrush uses this too — overlapping
        // stamps just saturate the mask towards alpha=1 (= full blur reveal).
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    Shader* s = nullptr;
    switch (currentBrush_.type) {
        case BrushType::Marker:    s = &markerShader_; break;
        case BrushType::Neon:      s = &neonShader_;   break;
        case BrushType::BlurBrush: s = &markerShader_; break;   // soft disc, same falloff as marker
        case BrushType::Pen:
        case BrushType::Arrow:
        case BrushType::Eraser:
        default:                   s = &penShader_;    break;
    }
    s->use();
    if (isBlur) {
        // Mask only — keep RGB at 0 so the mask FBO contents are unambiguous
        // when sampled (only .a carries information).
        s->setVec4("u_color", 0.f, 0.f, 0.f, 1.f);
    } else {
        s->setVec4("u_color",
                   currentBrush_.r * currentBrush_.a,
                   currentBrush_.g * currentBrush_.a,
                   currentBrush_.b * currentBrush_.a,
                   currentBrush_.a);
    }
    s->setVec2("u_layerSize", static_cast<float>(width_), static_cast<float>(height_));
    if (currentBrush_.type == BrushType::Pen ||
        currentBrush_.type == BrushType::Arrow ||
        currentBrush_.type == BrushType::Eraser) {
        s->setFloat("u_hardness", currentBrush_.hardness);
    }

    auto drawAt = [&](const StrokePoint& sp, float radiusOverride = -1.f) {
        const float r = (radiusOverride > 0 ? radiusOverride : currentBrush_.radiusPx) *
                        std::max(0.1f, sp.pressure);
        s->setVec2("u_centre", sp.x, sp.y);
        s->setFloat("u_radius", r);
        stampQuad_.draw();
    };

    if (currentBrush_.type == BrushType::Neon) {
        // Halo pass.
        s->setFloat("u_corePass", 0.f);
        for (const auto& sp : densified_) drawAt(sp, currentBrush_.radiusPx * 2.5f);
        // Core pass — same loop, smaller radius, rendered on top.
        s->setFloat("u_corePass", 1.f);
        for (const auto& sp : densified_) drawAt(sp);
    } else {
        for (const auto& sp : densified_) drawAt(sp);
    }

    glDisable(GL_BLEND);
    Framebuffer::bindDefault();
}

bool PaintEngine::pushSnapshot(Framebuffer& fbo,
                               std::vector<std::unique_ptr<Texture>>& stack,
                               bool capToMax) {
    auto snap = std::make_unique<Texture>();
    if (!snap->createEmpty(width_, height_)) return false;

    // Copy the FBO's colour attachment into the snapshot via glCopyTexSubImage2D
    // — fastest path on tile GPUs (no driver-level shader, no readback).
    fbo.bind();
    glBindTexture(GL_TEXTURE_2D, snap->id());
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width_, height_);
    glBindTexture(GL_TEXTURE_2D, 0);
    Framebuffer::bindDefault();

    if (capToMax && stack.size() >= kMaxSnapshots) {
        stack.erase(stack.begin());
    }
    stack.push_back(std::move(snap));
    return true;
}

void PaintEngine::restoreSnapshot(Framebuffer& target, const Texture& snap) {
    // Render the snapshot back into `target` using a one-shot passthrough
    // shader. Earlier iterations tried glCopyTexSubImage2D and a re-purposed
    // pen-shader stamp; the cleanest path that survived was a tiny dedicated
    // blit program kept as a static so it compiles once per process.
    target.bind();
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    static constexpr const char* kBlitVs = R"(#version 300 es
        layout(location = 0) in vec2 a_pos;
        out vec2 v_uv;
        void main() {
            v_uv = a_pos * 0.5 + 0.5;
            gl_Position = vec4(a_pos, 0.0, 1.0);
        }
    )";
    static constexpr const char* kBlitFs = R"(#version 300 es
        precision highp float;
        uniform sampler2D u_src;
        in vec2 v_uv;
        out vec4 fragColor;
        void main() { fragColor = texture(u_src, v_uv); }
    )";
    static Shader blitShader;
    if (!blitShader.isValid()) blitShader.compile(kBlitVs, kBlitFs);
    blitShader.use();
    snap.bind(GL_TEXTURE0);
    blitShader.setInt("u_src", 0);
    glDisable(GL_BLEND);
    stampQuad_.draw();
    Framebuffer::bindDefault();
}

namespace {
// Shared body for undoLayer / redoLayer. `current` is the FBO to roll back,
// `undoStack` holds the history of past states (top = current), `redoStack`
// gets the popped state pushed to it so redoLayer can put it back.
void undoLayerImpl(Framebuffer& current,
                   std::vector<std::unique_ptr<Texture>>& undoStack,
                   std::vector<std::unique_ptr<Texture>>& redoStack,
                   PaintEngine& self) {
    if (undoStack.empty()) return;
    if (undoStack.size() == 1) {
        // Only one snapshot = the just-finished stroke. Move it to redo so
        // the user can come back, then clear the layer.
        redoStack.push_back(std::move(undoStack.back()));
        undoStack.pop_back();
        current.bind();
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        Framebuffer::bindDefault();
        return;
    }
    // ≥ 2 snapshots: move the topmost (current state) to redo, then restore
    // the now-top (= state before the popped stroke) into the live FBO.
    redoStack.push_back(std::move(undoStack.back()));
    undoStack.pop_back();
    self.restoreSnapshot(current, *undoStack.back());
}

void redoLayerImpl(Framebuffer& current,
                   std::vector<std::unique_ptr<Texture>>& undoStack,
                   std::vector<std::unique_ptr<Texture>>& redoStack,
                   size_t cap,
                   PaintEngine& self) {
    if (redoStack.empty()) return;
    auto next = std::move(redoStack.back());
    redoStack.pop_back();
    self.restoreSnapshot(current, *next);
    if (undoStack.size() >= cap) undoStack.erase(undoStack.begin());
    undoStack.push_back(std::move(next));
}
} // namespace

void PaintEngine::undoPaintLayer() {
    undoLayerImpl(paintFbo_, undoableSnapshots_, redoableSnapshots_, *this);
}

void PaintEngine::redoPaintLayer() {
    redoLayerImpl(paintFbo_, undoableSnapshots_, redoableSnapshots_,
                  kMaxSnapshots, *this);
}

void PaintEngine::clearBlurMask() {
    if (!ready_) return;
    blurMaskFbo_.bind();
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    Framebuffer::bindDefault();
}

void PaintEngine::clear() {
    if (!ready_) return;
    paintFbo_.bind();
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    blurMaskFbo_.bind();
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    Framebuffer::bindDefault();
    undoableSnapshots_.clear();
    redoableSnapshots_.clear();
}

} // namespace photoedit
