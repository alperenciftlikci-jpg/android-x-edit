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
    void croppedOutputSize(int& w, int& h) const;

    // Paint API forwarders. Direct access to the engine via paint() lets the
    // JNI layer call beginStroke/extendStroke/endStroke/undo/redo/clear without
    // adding dozens of facade methods.
    PaintEngine* paint();
    TextLayer*   text();

    // Export the current pipeline output (filter chain → optional crop → FBO
    // → glReadPixels into outRgba).
    bool exportToBitmap(uint8_t* outRgba, int outWidth, int outHeight);

private:
    std::unique_ptr<Renderer> renderer_;
};

} // namespace photoedit
