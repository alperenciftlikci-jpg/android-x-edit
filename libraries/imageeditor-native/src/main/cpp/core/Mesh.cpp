// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "Mesh.h"

#include "Logger.h"

namespace photoedit {

namespace {
// NDC quad (x, y, u, v). Two triangles drawn as a strip.
//   v=1 ┌──────┐
//       │      │
//   v=0 └──────┘
//       u=0   u=1
constexpr float kQuadVerts[] = {
    // x      y     u     v
    -1.0f,  1.0f,  0.0f, 1.0f,  // top-left
    -1.0f, -1.0f,  0.0f, 0.0f,  // bottom-left
     1.0f,  1.0f,  1.0f, 1.0f,  // top-right
     1.0f, -1.0f,  1.0f, 0.0f,  // bottom-right
};
constexpr int kStride = 4 * sizeof(float);
} // namespace

Mesh::~Mesh() {
    release();
}

bool Mesh::createQuad() {
    release();
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    if (vao_ == 0 || vbo_ == 0) {
        PE_LOGE("Mesh: glGen failed");
        release();
        return false;
    }
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVerts), kQuadVerts, GL_STATIC_DRAW);

    // location 0 = position (vec2), location 1 = uv (vec2)
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, kStride, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, kStride, reinterpret_cast<void*>(sizeof(float) * 2));

    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return true;
}

void Mesh::release() {
    if (vbo_ != 0) {
        glDeleteBuffers(1, &vbo_);
        vbo_ = 0;
    }
    if (vao_ != 0) {
        glDeleteVertexArrays(1, &vao_);
        vao_ = 0;
    }
}

void Mesh::draw() const {
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

} // namespace photoedit
