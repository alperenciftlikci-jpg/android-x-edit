// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "Renderer.h"

#include "Logger.h"

namespace photoedit {

namespace {

// Standard NDC quad vertex shader. v_uv is unmodified — phase 2's CropEngine will
// inject a uv transform via a uniform matrix instead of editing this stage.
constexpr const char* kVertexSrc = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
)";

// Phase 1 fragment: pure passthrough. Filter shaders in Phase 2 fork from this
// (sample, modify, output) — keep the variable names consistent so the diff is
// just the post-sample math.
constexpr const char* kPassthroughFragSrc = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    fragColor = texture(u_tex, v_uv);
}
)";

} // namespace

Renderer::Renderer() = default;
Renderer::~Renderer() { release(); }

bool Renderer::init() {
    context_ = std::make_unique<GLContext>();
    if (!context_->init()) {
        PE_LOGE("Renderer: GLContext init failed");
        return false;
    }
    if (!passthrough_.compile(kVertexSrc, kPassthroughFragSrc)) {
        PE_LOGE("Renderer: passthrough shader compile failed");
        return false;
    }
    if (!quad_.createQuad()) {
        PE_LOGE("Renderer: quad mesh creation failed");
        return false;
    }
    if (!cropEngine_.init()) {
        PE_LOGE("Renderer: crop engine init failed");
        return false;
    }
    // Composite shader: paint layer is sampled with normal blend over the
    // filtered+cropped texture. Two textures, premultiplied src-over.
    static constexpr const char* kComposeFs = R"(#version 300 es
        precision highp float;
        uniform sampler2D u_base;
        uniform sampler2D u_overlay;
        in vec2 v_uv;
        out vec4 fragColor;
        void main() {
            vec4 b = texture(u_base, v_uv);
            vec4 o = texture(u_overlay, v_uv);
            fragColor = vec4(o.rgb + b.rgb * (1.0 - o.a), b.a);
        }
    )";
    if (!composeShader_.compile(R"(#version 300 es
        layout(location = 0) in vec2 a_pos;
        layout(location = 1) in vec2 a_uv;
        out vec2 v_uv;
        void main() {
            v_uv = a_uv;
            gl_Position = vec4(a_pos, 0.0, 1.0);
        }
    )", kComposeFs)) {
        PE_LOGE("Renderer: compose shader compile failed");
        return false;
    }

    // Blur reveal: composite the *committed* baked blur over the base first
    // (each baked stroke carries the sigma it was drawn with), then mix in the
    // active in-progress stroke using the live `blurredSource_` (current
    // slider sigma). Two-stage layering keeps already-finished strokes frozen
    // at their per-stroke intensity while still giving the user a true live
    // preview of the stroke they're currently dragging.
    static constexpr const char* kBlurRevealFs = R"(#version 300 es
        precision highp float;
        uniform sampler2D u_base;
        uniform sampler2D u_blurred;
        uniform sampler2D u_blurMask;
        uniform sampler2D u_committedBlur;
        in vec2 v_uv;
        out vec4 fragColor;
        void main() {
            vec4 base = texture(u_base, v_uv);
            vec4 committed = texture(u_committedBlur, v_uv); // straight RGBA
            vec3 blurredRgb = texture(u_blurred, v_uv).rgb;
            float maskA = texture(u_blurMask, v_uv).a;
            vec3 afterCommitted = mix(base.rgb, committed.rgb, committed.a);
            vec3 finalRgb = mix(afterCommitted, blurredRgb, maskA);
            fragColor = vec4(finalRgb, base.a);
        }
    )";
    if (!blurRevealShader_.compile(R"(#version 300 es
        layout(location = 0) in vec2 a_pos;
        layout(location = 1) in vec2 a_uv;
        out vec2 v_uv;
        void main() {
            v_uv = a_uv;
            gl_Position = vec4(a_pos, 0.0, 1.0);
        }
    )", kBlurRevealFs)) {
        PE_LOGE("Renderer: blur reveal shader compile failed");
        return false;
    }

    // Bake shader: read the committed layer + current live blurred source +
    // active stroke mask, write the new committed layer. RGB is a straight
    // mix at mask alpha (active overwrites committed where the stroke covered
    // it — last-write-wins, which matches the user's mental model when they
    // re-stroke over an existing blur with a different strength). Alpha uses
    // src-over so re-stroking an already-baked area keeps full coverage.
    static constexpr const char* kBakeBlurFs = R"(#version 300 es
        precision highp float;
        uniform sampler2D u_committed;
        uniform sampler2D u_blurred;
        uniform sampler2D u_mask;
        in vec2 v_uv;
        out vec4 fragColor;
        void main() {
            vec4 committed = texture(u_committed, v_uv);
            vec3 blurredRgb = texture(u_blurred, v_uv).rgb;
            float maskA = texture(u_mask, v_uv).a;
            vec3 newRgb = mix(committed.rgb, blurredRgb, maskA);
            float newA  = maskA + committed.a * (1.0 - maskA);
            fragColor = vec4(newRgb, newA);
        }
    )";
    if (!bakeBlurShader_.compile(R"(#version 300 es
        layout(location = 0) in vec2 a_pos;
        layout(location = 1) in vec2 a_uv;
        out vec2 v_uv;
        void main() {
            v_uv = a_uv;
            gl_Position = vec4(a_pos, 0.0, 1.0);
        }
    )", kBakeBlurFs)) {
        PE_LOGE("Renderer: bake blur shader compile failed");
        return false;
    }

    // Passthrough blit shader for the second half of the bake ping-pong
    // (scratch → committed) and for restoring undo snapshots into the
    // committed FBO. Same shader as kPassthroughFragSrc up top, but compiled
    // separately so the bake / undo paths don't fight with `passthrough_`
    // for uniform binding state if both happen in the same frame.
    if (!copyShader_.compile(kVertexSrc, kPassthroughFragSrc)) {
        PE_LOGE("Renderer: copy shader compile failed");
        return false;
    }
    return true;
}

bool Renderer::makeContextCurrent() {
    if (!context_) return false;
    if (!context_->makeCurrent()) {
        PE_LOGE("Renderer::makeContextCurrent failed (eglMakeCurrent)");
        return false;
    }
    return true;
}

void Renderer::release() {
    textLayer_.release();
    blurRevealShader_.release();
    bakeBlurShader_.release();
    copyShader_.release();
    blurredSource_.release();
    committedBlurFbo_.release();
    bakeScratchFbo_.release();
    undoableCommittedBlur_.clear();
    redoableCommittedBlur_.clear();
    paintEngine_.release();
    composeShader_.release();
    cropEngine_.release();
    filterChain_.release();
    sourceTex_.release();
    quad_.release();
    passthrough_.release();
    if (context_) {
        context_->release();
        context_.reset();
    }
    filterChainSized_ = false;
    paintEngineSized_ = false;
    blurredSourceSized_ = false;
    committedBlurSized_ = false;
    textLayerSized_ = false;
    sourceW_ = sourceH_ = 0;
}

bool Renderer::setSourceBitmap(const uint8_t* rgba, int width, int height) {
    if (!isReady()) {
        PE_LOGE("setSourceBitmap before init");
        return false;
    }
    if (!sourceTex_.create(width, height, rgba)) return false;
    sourceW_ = width;
    sourceH_ = height;

    // FilterChain ping-pong FBOs match the source dimensions. Resize lazily here
    // (and on first source upload) so the chain is always sized to the working
    // texture — reduces upscale/downscale aliasing across stages.
    if (!filterChainSized_) {
        if (!filterChain_.init(width, height)) {
            PE_LOGE("FilterChain init failed");
            return false;
        }
        filterChainSized_ = true;
    } else {
        if (!filterChain_.resize(width, height)) {
            PE_LOGE("FilterChain resize failed");
            return false;
        }
    }

    // PaintEngine layer matches the source dimensions too — paint coords are
    // pixel-space on the source bitmap.
    if (!paintEngineSized_) {
        if (!paintEngine_.init(width, height)) {
            PE_LOGE("PaintEngine init failed");
            return false;
        }
        paintEngineSized_ = true;
    } else {
        if (!paintEngine_.resize(width, height)) {
            PE_LOGE("PaintEngine resize failed");
            return false;
        }
    }

    if (!textLayerSized_) {
        if (!textLayer_.init(width, height)) {
            PE_LOGE("TextLayer init failed");
            return false;
        }
        textLayerSized_ = true;
    }

    // Pre-blur the source once per upload. The blur brush only ever needs to
    // mix the result in via mask — re-running the Gaussian on every export
    // would dominate the pipeline's GPU budget (25-tap * 2 passes).
    if (!blurredSourceSized_) {
        if (!blurredSource_.init(width, height)) {
            PE_LOGE("BlurredSource init failed");
            return false;
        }
        blurredSourceSized_ = true;
    } else {
        if (!blurredSource_.resize(width, height)) {
            PE_LOGE("BlurredSource resize failed");
            return false;
        }
    }
    if (!blurredSource_.process(sourceTex_, blurSigma_)) {
        PE_LOGE("BlurredSource process failed");
        return false;
    }

    // Committed-blur layer + bake ping-pong scratch — both source-res RGBA.
    // Cleared to (0,0,0,0) so the reveal shader's `mix(base, committed.rgb,
    // committed.a)` is a no-op for unblurred pixels (committed.a = 0). A new
    // source bitmap implicitly resets any prior baked blur, which matches
    // the user's expectation that "load a different photo" starts fresh.
    if (!committedBlurFbo_.create(width, height)) return false;
    if (!bakeScratchFbo_.create(width, height))   return false;
    committedBlurFbo_.bind();
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    Framebuffer::bindDefault();
    committedBlurSized_ = true;
    undoableCommittedBlur_.clear();
    redoableCommittedBlur_.clear();
    return true;
}

void Renderer::setFilterParams(const FilterParams& params) {
    filterParams_ = params;
}

void Renderer::setBlurSigma(float sigma) {
    // Sigma is interpreted in 1/8-res space inside BlurredSource — the shader
    // operates on the downsampled copy, so the full-res equivalent is ~8× this
    // value. Upper bound 4.4 (full-res ≈ 35) obscures text under the brush
    // without pushing into "smeared paint" territory at the top of the slider.
    // Lower bound 0.3 (full-res ≈ 2.4) lets the slider start at "barely-
    // anything" instead of locking the floor at a clearly visible blur —
    // anything smaller becomes dirac-like noise.
    if (sigma < 0.3f) sigma = 0.3f;
    if (sigma > 4.4f) sigma = 4.4f;
    blurSigma_ = sigma;
    if (blurredSourceSized_ && sourceTex_.isValid()) {
        if (!blurredSource_.process(sourceTex_, blurSigma_)) {
            PE_LOGE("BlurredSource re-process failed at sigma=%f", sigma);
        }
    }
}

void Renderer::setCropParams(const CropParams& params) {
    cropParams_ = params;
}

void Renderer::croppedOutputSize(int& w, int& h) const {
    cropEngine_.outputSize(cropParams_, sourceW_, sourceH_, w, h);
}

bool Renderer::exportToBitmap(uint8_t* outRgba, int outWidth, int outHeight) {
    if (!isReady() || !sourceTex_.isValid()) {
        PE_LOGE("exportToBitmap: renderer or source not ready");
        return false;
    }

    // Pipeline: source → filters → composite paint layer → (optional) crop → output.
    filterChain_.bumpFrameCounter();
    const Texture& filtered = filterChain_.process(sourceTex_, filterParams_);

    // Composite paint on top of filtered. We always do this pass — even when
    // there are no strokes, the paint layer is a transparent quad and the
    // composite is a one-pass blit. Keeps the surrounding pipeline branch-free.
    Framebuffer composedFbo;
    if (!composedFbo.create(sourceW_, sourceH_)) return false;
    composedFbo.bind();
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    composeShader_.use();
    filtered.bind(GL_TEXTURE0);
    paintEngine_.layerTexture().bind(GL_TEXTURE1);
    composeShader_.setInt("u_base", 0);
    composeShader_.setInt("u_overlay", 1);
    quad_.draw();
    Framebuffer::bindDefault();

    // Blur reveal pass: first layer the committed (baked, per-stroke-sigma)
    // blur over the composed image, then mix in the active in-progress
    // stroke using the live `blurredSource_` at the current slider sigma.
    // Always run — empty layers contribute zero. Output goes to `revealedFbo`,
    // which becomes the texture downstream stages consume.
    Framebuffer revealedFbo;
    if (!revealedFbo.create(sourceW_, sourceH_)) return false;
    revealedFbo.bind();
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    blurRevealShader_.use();
    composedFbo.texture().bind(GL_TEXTURE0);
    blurredSource_.texture().bind(GL_TEXTURE1);
    paintEngine_.blurMaskTexture().bind(GL_TEXTURE2);
    committedBlurFbo_.texture().bind(GL_TEXTURE3);
    blurRevealShader_.setInt("u_base", 0);
    blurRevealShader_.setInt("u_blurred", 1);
    blurRevealShader_.setInt("u_blurMask", 2);
    blurRevealShader_.setInt("u_committedBlur", 3);
    quad_.draw();
    Framebuffer::bindDefault();

    // Text composite — drawn on top of paint + blur, so a text item placed
    // over a stroke / blurred region remains readable.
    textLayer_.composite(revealedFbo);

    // Crop pass (only when params != identity).
    const Texture* postCrop = &revealedFbo.texture();
    Framebuffer cropFbo;
    if (!cropParams_.isIdentity()) {
        int cropW, cropH;
        cropEngine_.outputSize(cropParams_, sourceW_, sourceH_, cropW, cropH);
        if (!cropFbo.create(cropW, cropH)) return false;
        if (!cropEngine_.process(revealedFbo.texture(), cropParams_, cropFbo)) return false;
        postCrop = &cropFbo.texture();
    }

    Framebuffer fbo;
    if (!fbo.create(outWidth, outHeight)) {
        return false;
    }
    fbo.bind();
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);

    passthrough_.use();
    postCrop->bind(GL_TEXTURE0);
    passthrough_.setInt("u_tex", 0);
    quad_.draw();

    // Fence-sync the GPU work before readback. Without this glReadPixels does
    // an implicit glFinish-equivalent stall; with fence sync we can wait on a
    // bounded timeout and abort gracefully if the GPU is stuck (driver crash).
    GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (fence != nullptr) {
        // 1-second timeout — readback should complete in microseconds; if it
        // takes longer, something is very wrong (likely a GPU reset).
        constexpr GLuint64 kTimeoutNs = 1000ULL * 1000ULL * 1000ULL;
        GLenum waitResult = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, kTimeoutNs);
        glDeleteSync(fence);
        if (waitResult == GL_TIMEOUT_EXPIRED || waitResult == GL_WAIT_FAILED) {
            PE_LOGE("export: GPU fence wait failed (0x%x)", waitResult);
            Framebuffer::bindDefault();
            return false;
        }
    }
    glReadPixels(0, 0, outWidth, outHeight, GL_RGBA, GL_UNSIGNED_BYTE, outRgba);
    checkGlError("glReadPixels");

    Framebuffer::bindDefault();
    return true;
}

namespace {
// Snapshot the colour attachment of `fbo` into a fresh RGBA texture and push
// it onto `stack`, evicting the oldest entry FIFO-style when the cap is hit.
// Mirrors PaintEngine::pushSnapshot but lives here so the Renderer's
// committed-blur undo stack isn't tied to the PaintEngine instance.
bool snapshotFboTo(Framebuffer& fbo,
                   std::vector<std::unique_ptr<Texture>>& stack,
                   size_t cap) {
    auto snap = std::make_unique<Texture>();
    if (!snap->createEmpty(fbo.width(), fbo.height())) return false;
    fbo.bind();
    glBindTexture(GL_TEXTURE_2D, snap->id());
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, fbo.width(), fbo.height());
    glBindTexture(GL_TEXTURE_2D, 0);
    Framebuffer::bindDefault();
    if (stack.size() >= cap) stack.erase(stack.begin());
    stack.push_back(std::move(snap));
    return true;
}
} // namespace

void Renderer::commitActiveBlurStroke() {
    if (!committedBlurSized_) return;
    if (!paintEngine_.isReady()) return;

    // Ping-pong pass: read (committed, blurred, mask) → write scratch. Can't
    // sample committed and write to it in the same draw call (undefined per
    // GLES spec), hence the scratch hop.
    bakeScratchFbo_.bind();
    glViewport(0, 0, bakeScratchFbo_.width(), bakeScratchFbo_.height());
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_BLEND);
    bakeBlurShader_.use();
    committedBlurFbo_.texture().bind(GL_TEXTURE0);
    blurredSource_.texture().bind(GL_TEXTURE1);
    paintEngine_.blurMaskTexture().bind(GL_TEXTURE2);
    bakeBlurShader_.setInt("u_committed", 0);
    bakeBlurShader_.setInt("u_blurred", 1);
    bakeBlurShader_.setInt("u_mask", 2);
    quad_.draw();

    // Second pass: blit scratch back into the committed FBO so the next bake
    // / render sees the up-to-date state at its canonical texture slot.
    committedBlurFbo_.bind();
    glViewport(0, 0, committedBlurFbo_.width(), committedBlurFbo_.height());
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    copyShader_.use();
    bakeScratchFbo_.texture().bind(GL_TEXTURE0);
    copyShader_.setInt("u_tex", 0);
    quad_.draw();
    Framebuffer::bindDefault();

    // Push the post-bake state to undo. Same FIFO discipline as PaintEngine —
    // newest = back, oldest evicted from the front. Any pending redo history
    // gets dropped (a fresh stroke after undo invalidates the redo chain).
    snapshotFboTo(committedBlurFbo_, undoableCommittedBlur_, kMaxCommittedBlurSnapshots);
    redoableCommittedBlur_.clear();

    // Mask is no longer needed — the stroke is baked. Leaving it would
    // double-render the blur on the next frame (mask still opaque → live
    // blurredSource_ would re-reveal on top of the freshly committed pixels).
    paintEngine_.clearBlurMask();
}

void Renderer::undoBlurLayer() {
    if (!committedBlurSized_) return;
    if (undoableCommittedBlur_.empty()) return;

    // Same rule as PaintEngine's undo: top-of-undo is the CURRENT state. Move
    // it to redo, then either restore the now-top (= state before the popped
    // stroke) or clear the FBO if undo is now empty.
    redoableCommittedBlur_.push_back(std::move(undoableCommittedBlur_.back()));
    undoableCommittedBlur_.pop_back();

    committedBlurFbo_.bind();
    glViewport(0, 0, committedBlurFbo_.width(), committedBlurFbo_.height());
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!undoableCommittedBlur_.empty()) {
        glDisable(GL_BLEND);
        copyShader_.use();
        undoableCommittedBlur_.back()->bind(GL_TEXTURE0);
        copyShader_.setInt("u_tex", 0);
        quad_.draw();
    }
    Framebuffer::bindDefault();
}

void Renderer::redoBlurLayer() {
    if (!committedBlurSized_) return;
    if (redoableCommittedBlur_.empty()) return;

    auto next = std::move(redoableCommittedBlur_.back());
    redoableCommittedBlur_.pop_back();

    committedBlurFbo_.bind();
    glViewport(0, 0, committedBlurFbo_.width(), committedBlurFbo_.height());
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_BLEND);
    copyShader_.use();
    next->bind(GL_TEXTURE0);
    copyShader_.setInt("u_tex", 0);
    quad_.draw();
    Framebuffer::bindDefault();

    if (undoableCommittedBlur_.size() >= kMaxCommittedBlurSnapshots) {
        undoableCommittedBlur_.erase(undoableCommittedBlur_.begin());
    }
    undoableCommittedBlur_.push_back(std::move(next));
}

} // namespace photoedit
