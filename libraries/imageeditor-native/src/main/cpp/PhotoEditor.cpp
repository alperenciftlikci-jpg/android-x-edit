// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "PhotoEditor.h"

#include "core/Logger.h"

namespace photoedit {

PhotoEditor::PhotoEditor() = default;
PhotoEditor::~PhotoEditor() { release(); }

bool PhotoEditor::init() {
    renderer_ = std::make_unique<Renderer>();
    if (!renderer_->init()) {
        PE_LOGE("PhotoEditor: renderer init failed");
        renderer_.reset();
        return false;
    }
    return true;
}

void PhotoEditor::release() {
    if (renderer_) {
        renderer_->release();
        renderer_.reset();
    }
}

bool PhotoEditor::makeContextCurrent() {
    return renderer_ && renderer_->makeContextCurrent();
}

bool PhotoEditor::setSourceBitmap(const uint8_t* rgba, int width, int height) {
    if (!renderer_) return false;
    return renderer_->setSourceBitmap(rgba, width, height);
}

void PhotoEditor::setFilterParams(const FilterParams& params) {
    if (renderer_) renderer_->setFilterParams(params);
}

void PhotoEditor::setCropParams(const CropParams& params) {
    if (renderer_) renderer_->setCropParams(params);
}

void PhotoEditor::setBlurSigma(float sigma) {
    if (renderer_) renderer_->setBlurSigma(sigma);
}

void PhotoEditor::croppedOutputSize(int& w, int& h) const {
    if (renderer_) renderer_->croppedOutputSize(w, h);
    else { w = h = 0; }
}

PaintEngine* PhotoEditor::paint() {
    return renderer_ ? &renderer_->paint() : nullptr;
}

TextLayer* PhotoEditor::text() {
    return renderer_ ? &renderer_->text() : nullptr;
}

void PhotoEditor::commitActiveBlurStroke() {
    if (renderer_) renderer_->commitActiveBlurStroke();
}

void PhotoEditor::undoBlurLayer() {
    if (renderer_) renderer_->undoBlurLayer();
}

void PhotoEditor::redoBlurLayer() {
    if (renderer_) renderer_->redoBlurLayer();
}

bool PhotoEditor::exportToBitmap(uint8_t* outRgba, int outWidth, int outHeight) {
    if (!renderer_) return false;
    return renderer_->exportToBitmap(outRgba, outWidth, outHeight);
}

} // namespace photoedit
