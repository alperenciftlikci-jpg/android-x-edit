cmake_minimum_required(VERSION 3.22.1)
project(photoedit LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Phase 1 sources only. Filters / paint / text added in later phases by appending
# to these globs (or moving to explicit lists if globs become noisy).
set(SRC_CORE
    core/Logger.cpp
    core/GLContext.cpp
    core/Texture.cpp
    core/Framebuffer.cpp
    core/Shader.cpp
    core/Mesh.cpp
    core/Renderer.cpp
)

set(SRC_FILTERS
    filters/FilterChain.cpp
)

set(SRC_CROP
    crop/CropEngine.cpp
)

set(SRC_PAINT
    paint/PaintEngine.cpp
)

set(SRC_TEXT
    text/TextLayer.cpp
)

set(SRC_JNI
    jni/BitmapHelper.cpp
    jni/photoedit-jni.cpp
)

set(SRC_FACADE
    PhotoEditor.cpp
)

add_library(photoedit SHARED
    ${SRC_CORE}
    ${SRC_FILTERS}
    ${SRC_CROP}
    ${SRC_PAINT}
    ${SRC_TEXT}
    ${SRC_JNI}
    ${SRC_FACADE}
)

target_include_directories(photoedit PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Library probe targets — `find_library` returns absolute paths to the prebuilt
# NDK SOs. `target_link_libraries` then injects them into the link line.
find_library(GLES_LIB GLESv3)
find_library(EGL_LIB EGL)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(JNIGRAPHICS_LIB jnigraphics)

target_link_libraries(photoedit
    PRIVATE
    ${GLES_LIB}
    ${EGL_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
    ${JNIGRAPHICS_LIB}
)

# Release: -O3 + fast-math is the standard photo-pipeline tradeoff (denormals are
# flushed to zero, NaN propagation isn't strict). Debug: -O0 + symbols + asserts.
target_compile_options(photoedit PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    $<$<CONFIG:Release>:-O3 -ffast-math -fvisibility=hidden>
    $<$<CONFIG:Debug>:-O0 -g>
)

# Strip release SOs at link time so they don't bloat the APK.
target_link_options(photoedit PRIVATE
    $<$<CONFIG:Release>:-Wl,--strip-all>
)
