// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "Logger.h"

#include <GLES3/gl3.h>

namespace photoedit {

void checkGlError(const char* op) {
#ifndef NDEBUG
    GLenum err;
    while ((err = glGetError()) != GL_NO_ERROR) {
        PE_LOGE("GL error after %s: 0x%x", op, err);
    }
#else
    (void)op;
#endif
}

} // namespace photoedit
