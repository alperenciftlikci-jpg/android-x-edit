// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "Shader.h"

#include "Logger.h"
#include <vector>

namespace photoedit {

Shader::~Shader() {
    release();
}

GLuint Shader::compileStage(GLenum type, const char* src) {
    GLuint stage = glCreateShader(type);
    if (stage == 0) {
        PE_LOGE("glCreateShader failed");
        return 0;
    }
    glShaderSource(stage, 1, &src, nullptr);
    glCompileShader(stage);

    GLint compiled = GL_FALSE;
    glGetShaderiv(stage, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint logLen = 0;
        glGetShaderiv(stage, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> log(static_cast<size_t>(logLen) + 1, 0);
        glGetShaderInfoLog(stage, logLen, nullptr, log.data());
        PE_LOGE("Shader compile failed (type=0x%x): %s", type, log.data());
        glDeleteShader(stage);
        return 0;
    }
    return stage;
}

bool Shader::compile(const char* vertexSrc, const char* fragmentSrc) {
    release();
    GLuint vs = compileStage(GL_VERTEX_SHADER, vertexSrc);
    if (vs == 0) return false;
    GLuint fs = compileStage(GL_FRAGMENT_SHADER, fragmentSrc);
    if (fs == 0) {
        glDeleteShader(vs);
        return false;
    }

    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    // Stages can be detached + deleted right after link; the program retains them.
    glDetachShader(program_, vs);
    glDetachShader(program_, fs);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint logLen = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &logLen);
        std::vector<char> log(static_cast<size_t>(logLen) + 1, 0);
        glGetProgramInfoLog(program_, logLen, nullptr, log.data());
        PE_LOGE("Program link failed: %s", log.data());
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }
    return true;
}

void Shader::release() {
    if (program_ != 0) {
        glDeleteProgram(program_);
        program_ = 0;
    }
}

void Shader::use() const {
    glUseProgram(program_);
}

GLint Shader::location(const char* name) const {
    return glGetUniformLocation(program_, name);
}

void Shader::setInt(const char* name, int v) const {
    glUniform1i(location(name), v);
}

void Shader::setFloat(const char* name, float v) const {
    glUniform1f(location(name), v);
}

void Shader::setVec2(const char* name, float x, float y) const {
    glUniform2f(location(name), x, y);
}

void Shader::setVec4(const char* name, float x, float y, float z, float w) const {
    glUniform4f(location(name), x, y, z, w);
}

void Shader::setMat4(const char* name, const float* m4x4) const {
    glUniformMatrix4fv(location(name), 1, GL_FALSE, m4x4);
}

} // namespace photoedit
