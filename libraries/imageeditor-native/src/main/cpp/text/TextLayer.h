// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Pragmatic text rendering — instead of bundling FreeType + a font atlas, we let
// Kotlin (Android `StaticLayout` / `Canvas.drawText`) rasterise text into a
// bitmap, then upload that bitmap as a texture and render it as a transformed
// quad. This trades flexibility (LiveTune-style font loading) for a 90% smaller
// implementation and bulletproof Bidi / emoji / ligature support (since Android's
// text stack handles all of that natively).
//
// Each TextItem is identified by an integer ID set by Kotlin, so the Kotlin side
// can update / move / delete text by ID without re-uploading the same bitmap.
#pragma once

#include "../core/Framebuffer.h"
#include "../core/Mesh.h"
#include "../core/Shader.h"
#include "../core/Texture.h"

#include <memory>
#include <vector>

namespace photoedit {

struct TextItem {
    int   id          = 0;
    float x           = 0.f;     // pixel coords on the dst layer (top-left after rotation pivots from centre)
    float y           = 0.f;
    float scale       = 1.f;
    float rotationRad = 0.f;
    int   origW       = 0;       // bitmap dimensions, pre-scale
    int   origH       = 0;
    std::unique_ptr<Texture> texture;
};

class TextLayer {
public:
    TextLayer() = default;
    ~TextLayer();

    bool init(int width, int height);
    void release();

    // Add (or replace, by id) a text item. The bitmap is RGBA8 premultiplied; we
    // copy it into a GL texture so the caller can free it after the call.
    bool upsert(int id, const uint8_t* rgba, int w, int h,
                float x, float y, float scale, float rotationRad);

    bool remove(int id);
    void clear();

    // Render every active text item onto `dst`. We don't clear `dst` — that's
    // the caller's job (Renderer composites text on top of the paint+filter
    // result, so dst already has content).
    bool composite(Framebuffer& dst);

    bool isReady() const { return shader_.isValid(); }

private:
    int width_ = 0, height_ = 0;
    Shader shader_;
    Mesh   quad_;
    std::vector<TextItem> items_;

    TextItem* findById(int id);
};

} // namespace photoedit
