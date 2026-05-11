// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Reusable full-screen quad. Two triangles, four vertices, packed (x, y, u, v).
// Every filter/passthrough draw call uses this same mesh — the per-frame work is
// shader uniforms + texture binds, geometry is constant.
#pragma once

#include <GLES3/gl3.h>

namespace photoedit {

class Mesh {
public:
    Mesh() = default;
    ~Mesh();

    Mesh(const Mesh&) = delete;
    Mesh& operator=(const Mesh&) = delete;

    bool createQuad();
    void release();

    void draw() const;

    bool isValid() const { return vao_ != 0; }

private:
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
};

} // namespace photoedit
