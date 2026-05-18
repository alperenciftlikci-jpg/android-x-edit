// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Stamp-based paint engine. Stroke samples are densified with Catmull-Rom
// interpolation, then each interpolated sample triggers one full-quad stamp
// draw. We keep an off-screen FBO ("paint layer") that all strokes accumulate
// into; on undo we re-render from a snapshot stack.
#pragma once

#include "../core/Framebuffer.h"
#include "../core/Mesh.h"
#include "../core/Shader.h"
#include "../core/Texture.h"
#include "Brush.h"

#include <array>
#include <memory>
#include <vector>

namespace photoedit {

class PaintEngine {
public:
    PaintEngine() = default;
    ~PaintEngine();

    bool init(int width, int height);
    void release();

    // Resize the paint layer. Resets all strokes — caller is responsible for
    // re-issuing them if the canvas size genuinely changed mid-edit (rare).
    bool resize(int width, int height);

    // Stroke API. `begin` opens a new stroke, `extend` adds samples, `end`
    // commits to the layer + pushes an undo snapshot.
    void beginStroke(const BrushParams& brush, const StrokePoint& p);
    void extendStroke(const StrokePoint& p);
    void endStroke();

    // Layer-scoped undo / redo. Each brush only pushes snapshots into the
    // stack that matches the layer it draws to: colour brushes (Pen / Marker /
    // Neon / Arrow / Eraser) → paint stack. Blur strokes no longer snapshot
    // here — the Renderer owns the per-stroke baked blur layer and its own
    // undo stack (see `Renderer::undoBlurLayer`), so the two affordances stay
    // independent without PaintEngine having to track the bake state.
    void undoPaintLayer();
    void redoPaintLayer();
    void clear();

    // Wipe the blur-brush mask FBO (no undo snapshot — only called by the
    // Renderer immediately after baking an active stroke into its committed
    // layer, at which point keeping the now-redundant mask state would just
    // double-render the blur on the next frame).
    void clearBlurMask();

    bool hasStrokes() const {
        return !undoableSnapshots_.empty() || !redoableSnapshots_.empty();
    }

    // Type of the brush passed to the most recent `beginStroke`. Used by the
    // Renderer right after `endStroke()` to decide whether the just-ended
    // stroke needs a blur-bake pass; cheaper than threading the brush type
    // back through the JNI return value.
    BrushType lastBrushType() const { return currentBrush_.type; }

    // The accumulated paint layer (the texture the caller composites on top
    // of the filtered image). Result is RGBA premultiplied.
    const Texture& layerTexture() const { return paintFbo_.texture(); }

    // Blur-brush mask: a separate accumulation FBO that BlurBrush strokes write
    // to. The renderer's reveal pass mixes the pre-blurred source into the
    // composed image wherever this mask is opaque (alpha > 0).
    const Texture& blurMaskTexture() const { return blurMaskFbo_.texture(); }

    bool isReady() const { return ready_; }

    // Blit `snap` back into `target`. Public so the cpp's undoLayerImpl /
    // redoLayerImpl helpers (anonymous-namespace functions, not members) can
    // call it; everything else stays encapsulated.
    void restoreSnapshot(Framebuffer& target, const Texture& snap);

private:
    // Catmull-Rom densify between two samples to ~1px stamp spacing. Stores
    // results in `densified_` (cleared each call).
    void densify(const StrokePoint& p0, const StrokePoint& p1);

    // Render every densified sample as a stamp into `paintFbo_`.
    void renderStamps();

    // Snapshot a single FBO into a freshly allocated texture and push it onto
    // the given stack (with FIFO eviction at kMaxSnapshots).
    bool pushSnapshot(Framebuffer& fbo,
                      std::vector<std::unique_ptr<Texture>>& stack,
                      bool capToMax);

    bool ready_ = false;
    int width_ = 0, height_ = 0;

    // Compiled stamp shaders. One per brush type. Pen + Eraser share a shader
    // but use different blend func; Marker uses a soft-falloff variant; Neon
    // is two passes (glow + core); Arrow uses Pen with a head-cap appended.
    Shader penShader_;
    Shader markerShader_;
    Shader neonShader_;
    Mesh   stampQuad_;        // -1..1 NDC quad, position is set per-stamp via uniforms

    Framebuffer paintFbo_;       // accumulates all committed paint strokes
    Framebuffer scratchFbo_;     // current-stroke buffer (cleared at beginStroke)
    Framebuffer blurMaskFbo_;    // accumulates all BlurBrush strokes (mask in .a)

    BrushParams currentBrush_;
    std::vector<StrokePoint> rawPoints_;        // user samples for the active stroke
    std::vector<StrokePoint> densified_;        // Catmull-Rom interpolated stamps
    StrokePoint lastSent_{};
    bool inStroke_ = false;

    // Undo/redo: each entry is a Texture (move-only), capped to N. Pushing past
    // N drops the oldest entry (FIFO); popping from undo moves to redo. Paint
    // strokes (Pen / Marker / Neon / Arrow / Eraser) live here; blur strokes
    // are baked into the Renderer's committed-blur layer at stroke end and
    // its undo stack handles their rollback.
    std::vector<std::unique_ptr<Texture>> undoableSnapshots_;
    std::vector<std::unique_ptr<Texture>> redoableSnapshots_;
    static constexpr size_t kMaxSnapshots = 20;

    bool compileShaders();
    bool initFbos(int width, int height);
};

} // namespace photoedit
