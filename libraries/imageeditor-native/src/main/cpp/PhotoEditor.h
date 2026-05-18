// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Public C++ facade. Phase 1 only exposes setSource + exportToBitmap; later
// phases will add filter / crop / paint / text APIs (see brief §4).
#pragma once

#include "core/Renderer.h"
#include "crop/CropParams.h"
#include "filters/FilterParams.h"
#include "paint/Brush.h"
#include "paint/PaintEngine.h"
#include "text/TextLayer.h"

#include <cstdint>
#include <memory>

namespace photoedit {

class PhotoEditor {
public:
    PhotoEditor();
    ~PhotoEditor();

    bool init();
    void release();

    /** Re-bind the EGL context to the calling thread. JNI calls this at the top
     *  of every entry point that touches GL. */
    bool makeContextCurrent();

    bool setSourceBitmap(const uint8_t* rgba, int width, int height);
    void setFilterParams(const FilterParams& params);
    void setCropParams(const CropParams& params);
    /** Re-run the BlurredSource pre-blur with the given Gaussian sigma. Used by
     *  the Blur-tab "Strength" slider to retune how hard text / detail is hidden
     *  under blur-brush strokes; clamped to a safe range inside the renderer. */
    void setBlurSigma(float sigma);
    void croppedOutputSize(int& w, int& h) const;

    // Paint API forwarders. Direct access to the engine via paint() lets the
    // JNI layer call beginStroke/extendStroke/endStroke/undo/redo/clear without
    // adding dozens of facade methods.
    PaintEngine* paint();
    TextLayer*   text();

    // Bake the active blur stroke at the renderer's current sigma into the
    // committed-blur layer. JNI invokes this right after `paint()->endStroke()`
    // when the just-ended stroke was a BlurBrush, so each stroke locks in the
    // strength that was live when the user lifted their finger.
    void commitActiveBlurStroke();

    // Undo / redo for the committed blur layer. The blur stack lives on the
    // Renderer (not PaintEngine) because the committed layer is what changes
    // across strokes now — the in-progress mask is cleared each commit.
    void undoBlurLayer();
    void redoBlurLayer();

    // Export the current pipeline output (filter chain → optional crop → FBO
    // → glReadPixels into outRgba).
    bool exportToBitmap(uint8_t* outRgba, int outWidth, int outHeight);

private:
    std::unique_ptr<Renderer> renderer_;
};

} // namespace photoedit
