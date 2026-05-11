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
    return true;
}

void Renderer::setFilterParams(const FilterParams& params) {
    filterParams_ = params;
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

    // Text composite — drawn on top of paint, so a text item placed over a
    // stroke remains readable.
    textLayer_.composite(composedFbo);

    // Crop pass (only when params != identity).
    const Texture* postCrop = &composedFbo.texture();
    Framebuffer cropFbo;
    if (!cropParams_.isIdentity()) {
        int cropW, cropH;
        cropEngine_.outputSize(cropParams_, sourceW_, sourceH_, cropW, cropH);
        if (!cropFbo.create(cropW, cropH)) return false;
        if (!cropEngine_.process(composedFbo.texture(), cropParams_, cropFbo)) return false;
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

} // namespace photoedit
