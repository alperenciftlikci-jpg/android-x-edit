// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Phase 1 renderer: identity passthrough (source → output) with no filtering.
// Phase 2 will replace `render()`'s body with `FilterChain::process()` + crop +
// paint compositing.
#pragma once

#include "../blur/BlurredSource.h"
#include "../crop/CropEngine.h"
#include "../crop/CropParams.h"
#include "../filters/FilterChain.h"
#include "../filters/FilterParams.h"
#include "../paint/Brush.h"
#include "../paint/PaintEngine.h"
#include "../text/TextLayer.h"
#include "Framebuffer.h"
#include "GLContext.h"
#include "Mesh.h"
#include "Shader.h"
#include "Texture.h"

#include <cstdint>
#include <memory>
#include <vector>

namespace photoedit {

class Renderer {
public:
    Renderer();
    ~Renderer();

    bool init();
    void release();

    // Re-bind the EGL context to the calling thread. Must be called from JNI entry
    // points before any GL work, because Kotlin's `Dispatchers.IO` can hop the call
    // across worker threads and EGL contexts are TLS-bound.
    bool makeContextCurrent();

    bool setSourceBitmap(const uint8_t* rgba, int width, int height);
    void setFilterParams(const FilterParams& params);
    void setCropParams(const CropParams& params);

    // Re-run the BlurredSource pre-blur with a new Gaussian sigma. Caller invokes
    // this when the user drags the blur-brush "Strength" slider. The pre-blur runs
    // once per call (a 2-pass separable Gaussian on the source texture), so this is
    // fine to call on slider release; mid-drag throttling stays the caller's
    // responsibility. Sigma is clamped to a sane band so a runaway slider can't
    // request a 1000-pixel kernel.
    void setBlurSigma(float sigma);

    // Output size after the active crop transform (or source size if identity).
    void croppedOutputSize(int& w, int& h) const;

    PaintEngine& paint() { return paintEngine_; }
    TextLayer&   text()  { return textLayer_; }

    // Render the source through the filter chain into an off-screen FBO, then
    // read back to `outRgba` (RGBA8, tight stride).
    bool exportToBitmap(uint8_t* outRgba, int outWidth, int outHeight);

    bool isReady() const { return passthrough_.isValid() && quad_.isValid(); }

    // Bake the active (in-progress) blur stroke's mask into the committed blur
    // layer using the renderer's *current* sigma, then clear the mask. Call
    // this immediately after `PaintEngine::endStroke()` when the just-ended
    // stroke was a BlurBrush — each stroke gets baked at the sigma that was
    // live the moment the user lifted their finger, so changing the Strength
    // slider afterwards only affects subsequent strokes (Telegram-style
    // per-stroke blur intensity). Also snapshots the pre-bake committed FBO
    // so `undoBlurLayer()` can roll back. No-op if no blur work has happened.
    void commitActiveBlurStroke();

    // Roll back / re-apply the most recently committed blur stroke. Operates
    // on `committedBlurFbo_` (a baked RGBA layer) — the active mask is
    // already cleared at this point, so there's nothing to restore on the
    // PaintEngine side.
    void undoBlurLayer();
    void redoBlurLayer();

private:
    // Off-screen EGL context owned by Phase 1 — Phase 2's GLSurfaceView wiring
    // will replace this with the GLSurfaceView's own context.
    std::unique_ptr<GLContext> context_;
    Shader passthrough_;
    Mesh   quad_;
    Texture sourceTex_;
    FilterChain filterChain_;
    FilterParams filterParams_;
    bool filterChainSized_ = false;

    CropEngine cropEngine_;
    CropParams cropParams_;
    int sourceW_ = 0, sourceH_ = 0;

    PaintEngine paintEngine_;
    bool paintEngineSized_ = false;
    Shader composeShader_;        // src-over composite of paint layer onto filtered+cropped

    // Blur brush pipeline. Source is blurred once on upload (BlurredSource), then
    // BlurBrush strokes accumulate into a mask FBO inside PaintEngine; the
    // `blurRevealShader_` pass mixes the blurred copy back in wherever the mask
    // is opaque. Pre-blurring on upload keeps per-frame export cost flat —
    // mask-stroke editing becomes a cheap mix(), not a per-frame Gaussian.
    BlurredSource blurredSource_;
    bool blurredSourceSized_ = false;
    Shader blurRevealShader_;
    // Tunable strength of the pre-blur. Interpreted in 1/8-res sigma space
    // inside BlurredSource (so the full-res equivalent is ~8× this number);
    // 0.77 here ≈ effective full-res sigma 6, matching the Kotlin state's
    // initial `blurBrushStrength = 0.1` on the new [0.3..5] low-res band —
    // a barely-there default that lets the user dial up if they want more.
    float blurSigma_ = 0.77f;

    // Per-stroke committed blur layer + scratch. Each blur stroke is baked
    // here at the sigma it was drawn with — once baked, sliding the Strength
    // slider can't disturb it. `committedBlurFbo_` carries straight RGBA:
    // alpha doubles as "is there baked blur here" so the reveal pass can
    // composite it over the source with a simple mix(). `bakeScratchFbo_`
    // ping-pongs during the bake because we both read from and write to the
    // committed FBO and a self-loop sampler would be undefined.
    Framebuffer committedBlurFbo_;
    Framebuffer bakeScratchFbo_;
    bool committedBlurSized_ = false;
    Shader bakeBlurShader_;    // mix(committed, blurredActive, mask.a) → scratch
    Shader copyShader_;        // passthrough blit (scratch → committed)

    // Undo / redo for the committed blur layer. Snapshots are full-res RGBA
    // textures captured *before* a bake, so undo restores the prior state
    // verbatim. Same memory profile as the prior blur-mask snapshot stack —
    // both are RGBA at source resolution — so no regression vs the pre-bake
    // design.
    std::vector<std::unique_ptr<Texture>> undoableCommittedBlur_;
    std::vector<std::unique_ptr<Texture>> redoableCommittedBlur_;
    static constexpr size_t kMaxCommittedBlurSnapshots = 20;

    TextLayer textLayer_;
    bool textLayerSized_ = false;
};

} // namespace photoedit
