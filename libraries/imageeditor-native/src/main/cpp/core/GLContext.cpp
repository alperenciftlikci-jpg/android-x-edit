// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "GLContext.h"

#include "Logger.h"

namespace photoedit {

GLContext::~GLContext() {
    release();
}

bool GLContext::init() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        PE_LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(display_, &major, &minor)) {
        PE_LOGE("eglInitialize failed");
        return false;
    }
    PE_LOGI("EGL initialized %d.%d", major, minor);

    // Pick an RGBA8 / depth-less / pbuffer-capable config. We don't allocate a
    // depth buffer because every rendering pass is 2D — saves memory on the
    // pbuffer and on every FBO allocation later.
    // EGL_OPENGL_ES3_BIT is part of EGL 1.5 (NDK r17+); KHR variant only exists if you include
    // <EGL/eglext.h>. We don't need extension headers for ES 3.0 here, so use the core token.
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE,
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) || numConfigs < 1) {
        PE_LOGE("eglChooseConfig found no matching configs");
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 0,
        EGL_NONE,
    };
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        PE_LOGE("eglCreateContext (ES 3) failed");
        return false;
    }

    const EGLint pbufferAttribs[] = {
        EGL_WIDTH,  1,
        EGL_HEIGHT, 1,
        EGL_NONE,
    };
    surface_ = eglCreatePbufferSurface(display_, config_, pbufferAttribs);
    if (surface_ == EGL_NO_SURFACE) {
        PE_LOGE("eglCreatePbufferSurface failed");
        return false;
    }

    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        PE_LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    PE_LOGI("GL: %s | %s",
            reinterpret_cast<const char*>(glGetString(GL_VERSION)),
            reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
    return true;
}

void GLContext::release() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, surface_);
            surface_ = EGL_NO_SURFACE;
        }
        if (context_ != EGL_NO_CONTEXT) {
            eglDestroyContext(display_, context_);
            context_ = EGL_NO_CONTEXT;
        }
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }
}

bool GLContext::makeCurrent() {
    if (!isValid()) return false;
    return eglMakeCurrent(display_, surface_, surface_, context_) == EGL_TRUE;
}

void GLContext::releaseCurrent() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

bool GLContext::isContextLost() const {
    if (!isValid()) return true;
    // glGetGraphicsResetStatus / GL_KHR_robustness aren't always available; the
    // portable signal is `eglMakeCurrent` returning EGL_CONTEXT_LOST. This is a
    // const method so we don't actually swap state — we just probe the bind.
    EGLBoolean result = eglMakeCurrent(display_, surface_, surface_, context_);
    if (result == EGL_TRUE) return false;
    EGLint err = eglGetError();
    if (err == EGL_CONTEXT_LOST) {
        PE_LOGE("EGL context lost");
        return true;
    }
    PE_LOGE("eglMakeCurrent probe failed: 0x%x", err);
    return false;
}

} // namespace photoedit
