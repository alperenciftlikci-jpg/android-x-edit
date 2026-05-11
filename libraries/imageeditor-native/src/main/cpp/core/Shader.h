// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// GL program wrapper. Compile a vertex+fragment pair and expose convenience
// uniform setters. Phase 2's filter cache will store these by their fragment
// source hash so identical shaders aren't re-linked.
#pragma once

#include <GLES3/gl3.h>
#include <string>

namespace photoedit {

class Shader {
public:
    Shader() = default;
    ~Shader();

    Shader(const Shader&) = delete;
    Shader& operator=(const Shader&) = delete;

    bool compile(const char* vertexSrc, const char* fragmentSrc);
    void release();

    void use() const;

    GLint location(const char* name) const;
    void setInt    (const char* name, int v) const;
    void setFloat  (const char* name, float v) const;
    void setVec2   (const char* name, float x, float y) const;
    void setVec4   (const char* name, float x, float y, float z, float w) const;
    void setMat4   (const char* name, const float* m4x4) const;

    GLuint program() const { return program_; }
    bool isValid()  const { return program_ != 0; }

private:
    static GLuint compileStage(GLenum type, const char* src);
    GLuint program_ = 0;
};

} // namespace photoedit
