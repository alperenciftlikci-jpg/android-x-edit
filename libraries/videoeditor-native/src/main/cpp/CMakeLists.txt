cmake_minimum_required(VERSION 3.22.1)
project(videoedit LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# FFmpeg (Telegram's prebuilt static libs).
# ---------------------------------------------------------------------------
# Telegram ships per-ABI .a files for libavformat/libavcodec/libavutil/etc. They
# were built with a custom config (see jni/ffmpeg/build_ffmpeg/) — small subset
# of demuxers/decoders/filters tuned for messenger video. We link them into our
# `videoedit` shared lib statically; the resulting libvideoedit.so is the only
# binary the app loads at runtime.
if(NOT DEFINED TELEGRAM_FFMPEG_DIR)
    message(FATAL_ERROR
        "TELEGRAM_FFMPEG_DIR not set. Pass via -Ptelegram.ffmpeg.dir=... in Gradle, "
        "or set the gradle property."
    )
endif()

set(FFMPEG_INCLUDE "${TELEGRAM_FFMPEG_DIR}/include")
set(FFMPEG_LIB_DIR "${TELEGRAM_FFMPEG_DIR}/${ANDROID_ABI}")

if(NOT EXISTS "${FFMPEG_INCLUDE}/libavformat/avformat.h")
    message(FATAL_ERROR "FFmpeg headers not found at ${FFMPEG_INCLUDE}")
endif()
if(NOT EXISTS "${FFMPEG_LIB_DIR}/libavcodec.a")
    message(FATAL_ERROR "FFmpeg libs not found at ${FFMPEG_LIB_DIR}")
endif()

# Each FFmpeg sublib as an IMPORTED static target. Order matters at link time —
# avformat depends on avcodec which depends on avutil; we list them in that
# order below so the linker resolves symbols correctly on a single pass.
function(import_static_lib name path)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${path}")
endfunction()

import_static_lib(ffmpeg_avformat   ${FFMPEG_LIB_DIR}/libavformat.a)
import_static_lib(ffmpeg_avcodec    ${FFMPEG_LIB_DIR}/libavcodec.a)
import_static_lib(ffmpeg_swresample ${FFMPEG_LIB_DIR}/libswresample.a)
import_static_lib(ffmpeg_swscale    ${FFMPEG_LIB_DIR}/libswscale.a)
import_static_lib(ffmpeg_avutil     ${FFMPEG_LIB_DIR}/libavutil.a)

# Optional codecs — these may or may not exist depending on Telegram's build
# config (e.g. arm64 has all of them, x86 might miss vpx). Add them only if the
# .a file is present so `find_library`/import doesn't fail on missing platforms.
foreach(opt_lib avresample dav1d vpx)
    set(opt_path "${FFMPEG_LIB_DIR}/lib${opt_lib}.a")
    if(EXISTS "${opt_path}")
        import_static_lib(ffmpeg_${opt_lib} ${opt_path})
        list(APPEND OPTIONAL_FFMPEG_LIBS ffmpeg_${opt_lib})
    endif()
endforeach()

# ---------------------------------------------------------------------------
# Our sources
# ---------------------------------------------------------------------------
set(SRC_CORE
    core/Logger.cpp
)

set(SRC_DECODER
    decoder/VideoDecoder.cpp
)

set(SRC_ENCODER
    encoder/VideoEncoder.cpp
)

set(SRC_PIPELINE
    pipeline/VideoEditor.cpp
    pipeline/SimpleFilters.cpp
)

set(SRC_JNI
    jni/videoedit-jni.cpp
)

add_library(videoedit SHARED
    ${SRC_CORE}
    ${SRC_DECODER}
    ${SRC_ENCODER}
    ${SRC_PIPELINE}
    ${SRC_JNI}
)

target_include_directories(videoedit PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${FFMPEG_INCLUDE}
)

# Android system libraries.
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(JNIGRAPHICS_LIB jnigraphics)
find_library(MEDIANDK_LIB mediandk)        # NDK's MediaCodec C API
find_library(Z_LIB z)
find_library(GLES_LIB GLESv3)
find_library(EGL_LIB EGL)

# Link order: our higher-level sources don't need to come first; FFmpeg group
# closure handles intra-FFmpeg deps via -Wl,--start-group ... --end-group.
target_link_libraries(videoedit
    PRIVATE
    -Wl,--start-group
    ffmpeg_avformat
    ffmpeg_avcodec
    ffmpeg_swresample
    ffmpeg_swscale
    ffmpeg_avutil
    ${OPTIONAL_FFMPEG_LIBS}
    -Wl,--end-group
    ${LOG_LIB}
    ${ANDROID_LIB}
    ${JNIGRAPHICS_LIB}
    ${MEDIANDK_LIB}
    ${Z_LIB}
    ${GLES_LIB}
    ${EGL_LIB}
)

target_compile_options(videoedit PRIVATE
    -Wall
    -Wno-unused-parameter
    -Wno-deprecated-declarations    # FFmpeg has plenty of `attribute_deprecated` even on stable APIs
    $<$<CONFIG:Release>:-O3 -fvisibility=hidden>
    $<$<CONFIG:Debug>:-O0 -g>
)

target_link_options(videoedit PRIVATE
    $<$<CONFIG:Release>:-Wl,--strip-all>
)
