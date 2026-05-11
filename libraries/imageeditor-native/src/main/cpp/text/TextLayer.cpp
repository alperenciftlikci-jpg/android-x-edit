// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "TextLayer.h"

#include "../core/Logger.h"

#include <algorithm>
#include <cmath>

namespace photoedit {

namespace {

// Vertex shader: takes the standard NDC quad and transforms it for one text
// item. Rotation pivots around the item's centre, then translation places the
// rotated rect at (x, y) — top-left in the layer's pixel coords.
constexpr const char* kVs = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
uniform vec2  u_layerSize;     // dst pixel size
uniform vec2  u_origin;        // top-left of the (pre-rotate) bbox in dst pixels
uniform vec2  u_size;          // pixel size of the textured quad after scale
uniform float u_rotation;      // radians
out vec2 v_uv;
void main() {
    // Map a_pos from NDC [-1,1] to a unit-coord pivot at quad centre [0,1].
    vec2 unit = a_pos * 0.5 + 0.5;       // 0..1
    vec2 p    = (unit - 0.5) * u_size;   // pixels, centred on origin
    // Rotate around centre.
    float c = cos(u_rotation);
    float s = sin(u_rotation);
    vec2 r = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
    // Translate to the item's top-left + half-size (so the bbox aligns to u_origin).
    vec2 pixel = r + u_origin + u_size * 0.5;
    // To NDC for the layer's viewport.
    vec2 ndc = (pixel / u_layerSize) * 2.0 - 1.0;
    v_uv = a_uv;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
)";

// Premultiplied src-over composite. Text bitmaps from Kotlin are RGBA premul.
constexpr const char* kFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    fragColor = texture(u_tex, v_uv);
}
)";

} // namespace

TextLayer::~TextLayer() { release(); }

bool TextLayer::init(int width, int height) {
    width_ = width;
    height_ = height;
    if (!quad_.createQuad()) return false;
    if (!shader_.compile(kVs, kFs)) {
        PE_LOGE("TextLayer: shader compile failed");
        return false;
    }
    return true;
}

void TextLayer::release() {
    items_.clear();
    shader_.release();
    quad_.release();
}

TextItem* TextLayer::findById(int id) {
    auto it = std::find_if(items_.begin(), items_.end(),
                           [id](const TextItem& t) { return t.id == id; });
    return it == items_.end() ? nullptr : &*it;
}

bool TextLayer::upsert(int id, const uint8_t* rgba, int w, int h,
                       float x, float y, float scale, float rotationRad) {
    if (rgba == nullptr || w <= 0 || h <= 0) return false;

    TextItem* existing = findById(id);
    if (existing != nullptr) {
        // Re-upload only if the bitmap dimensions changed (Kotlin can call this
        // for both content-edit and pure-transform updates — for the latter,
        // skipping the texture upload halves the call cost).
        if (existing->origW != w || existing->origH != h) {
            existing->texture = std::make_unique<Texture>();
            if (!existing->texture->create(w, h, rgba)) return false;
            existing->origW = w;
            existing->origH = h;
        } else {
            // Same size — fast path, just re-upload pixels.
            existing->texture->bind();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        }
        existing->x = x;
        existing->y = y;
        existing->scale = scale;
        existing->rotationRad = rotationRad;
        return true;
    }

    TextItem item;
    item.id = id;
    item.x = x;
    item.y = y;
    item.scale = scale;
    item.rotationRad = rotationRad;
    item.origW = w;
    item.origH = h;
    item.texture = std::make_unique<Texture>();
    if (!item.texture->create(w, h, rgba)) return false;
    items_.push_back(std::move(item));
    return true;
}

bool TextLayer::remove(int id) {
    const auto before = items_.size();
    items_.erase(std::remove_if(items_.begin(), items_.end(),
                                [id](const TextItem& t) { return t.id == id; }),
                 items_.end());
    return items_.size() != before;
}

void TextLayer::clear() {
    items_.clear();
}

bool TextLayer::composite(Framebuffer& dst) {
    if (!isReady()) return false;
    if (items_.empty()) return true;

    dst.bind();
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);   // premultiplied src-over

    shader_.use();
    shader_.setVec2("u_layerSize",
                    static_cast<float>(dst.width()),
                    static_cast<float>(dst.height()));
    shader_.setInt("u_tex", 0);

    for (const auto& it : items_) {
        if (!it.texture || !it.texture->isValid()) continue;
        const float sw = it.origW * it.scale;
        const float sh = it.origH * it.scale;
        shader_.setVec2("u_origin", it.x, it.y);
        shader_.setVec2("u_size", sw, sh);
        shader_.setFloat("u_rotation", it.rotationRad);
        it.texture->bind(GL_TEXTURE0);
        quad_.draw();
    }

    glDisable(GL_BLEND);
    Framebuffer::bindDefault();
    return true;
}

} // namespace photoedit
