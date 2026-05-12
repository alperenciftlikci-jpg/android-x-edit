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

    // Output size after the active crop transform (or source size if identity).
    void croppedOutputSize(int& w, int& h) const;

    PaintEngine& paint() { return paintEngine_; }
    TextLayer&   text()  { return textLayer_; }

    // Render the source through the filter chain into an off-screen FBO, then
    // read back to `outRgba` (RGBA8, tight stride).
    bool exportToBitmap(uint8_t* outRgba, int outWidth, int outHeight);

    bool isReady() const { return passthrough_.isValid() && quad_.isValid(); }

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
    // Tunable strength of the pre-blur (Gaussian sigma in source pixels). 6
    // gives a visibly blurred but still-recognisable photo — matches Telegram's
    // blur brush intensity.
    float blurSigma_ = 6.f;

    TextLayer textLayer_;
    bool textLayerSized_ = false;
};

} // namespace photoedit
