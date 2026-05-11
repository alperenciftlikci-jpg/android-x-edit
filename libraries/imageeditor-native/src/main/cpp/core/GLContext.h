// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Off-screen EGL context owner. Phase 1 only needs an off-screen pbuffer surface so
// we can create textures / FBOs and read pixels back; on-screen rendering is wired
// in via GLSurfaceView's own EGL context (which Phase 1 does not yet integrate).
#pragma once

#include <EGL/egl.h>
#include <GLES3/gl3.h>

namespace photoedit {

class GLContext {
public:
    GLContext() = default;
    ~GLContext();
    GLContext(const GLContext&) = delete;
    GLContext& operator=(const GLContext&) = delete;

    // Create a 1×1 pbuffer-backed context. Real rendering goes to FBOs whose size
    // is decoupled from the surface — surface size is irrelevant once an FBO is
    // bound, the pbuffer just exists so eglMakeCurrent has a draw target.
    bool init();

    void release();

    bool makeCurrent();
    void releaseCurrent();

    bool isValid() const { return context_ != EGL_NO_CONTEXT; }

    // EGL context-loss check. Background tab kills + GPU driver resets surface
    // the context as EGL_CONTEXT_LOST, after which any GL call segfaults. We
    // detect via eglMakeCurrent's error and rebuild on next render call.
    bool isContextLost() const;

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLConfig  config_  = nullptr;
};

} // namespace photoedit
