// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Compiles every filter shader once on init, then walks them in fixed order on
// `process()`. Filters that the params indicate are at-identity are skipped so a
// neutral pipeline costs zero FBO passes — only the active filters draw.
//
// Pipeline order matters because compositions aren't commutative:
//   tone (BCS+exp) → warmth → fade → highlights/shadows → vignette → grain →
//   sharpen → tint → curves
// Tone first so subsequent filters operate on a balanced base. Curves last so a
// hand-drawn LUT can be the final word over everything else.
#pragma once

#include "../core/Framebuffer.h"
#include "../core/Mesh.h"
#include "../core/Shader.h"
#include "../core/Texture.h"
#include "FilterParams.h"

#include <array>
#include <cstdint>

namespace photoedit {

class FilterChain {
public:
    FilterChain() = default;
    ~FilterChain();

    bool init(int width, int height);
    void release();

    // Resize the ping-pong FBOs. Called when the source bitmap dimensions change.
    bool resize(int width, int height);

    // Run the active filters; returns the texture that holds the final result.
    // If every filter is at-identity, returns a reference to `source`.
    const Texture& process(const Texture& source, const FilterParams& params);

    // Frame counter for the grain shader's PRNG seed. Bumped each render so the
    // noise field actually moves between frames.
    void bumpFrameCounter() { ++frameCounter_; }

private:
    bool ready_ = false;
    int width_ = 0, height_ = 0;
    uint32_t frameCounter_ = 0;

    // Ping-pong pair. We alternate src↔dst as filters fire.
    std::array<Framebuffer, 2> pingPong_;

    // One Mesh shared by every filter pass.
    Mesh quad_;

    // Compiled programs. Order matches the enum below; index in tandem.
    enum class Stage : int {
        ToneBcs = 0,           // exposure + brightness + contrast + saturation
        Warmth,
        Fade,
        HighlightsShadows,
        Vignette,
        Grain,
        Sharpen,
        Tint,
        Blur,                  // Telegram-style radial / linear focal blur
        Curves,
        kCount,
    };
    std::array<Shader, static_cast<size_t>(Stage::kCount)> shaders_;

    // 256x1 RGBA texture for the curves LUT. Re-uploaded only when the LUT
    // identity flag flips or values change (FilterChain compares the tag byte).
    Texture curvesLut_;
    bool curvesLutDirty_ = true;

    // Helpers
    bool compileAll();
    bool isStageActive(Stage stage, const FilterParams& p) const;
    void applyUniforms(Stage stage, Shader& s, const FilterParams& p);
    void uploadCurvesLut(const FilterParams& p);
};

} // namespace photoedit
