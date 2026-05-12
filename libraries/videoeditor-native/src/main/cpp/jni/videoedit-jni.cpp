// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Phase 1: minimal JNI surface. `nativeProbe()` returns the FFmpeg version
// string — confirms the link succeeded and the Telegram-prebuilt FFmpeg is
// reachable from JNI.
#include <jni.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
}

#include "../core/Logger.h"
#include "../decoder/VideoDecoder.h"
#include "../pipeline/VideoEditor.h"
#include "../pipeline/StreamCopyTrim.h"

#include <android/bitmap.h>
#include <string>

namespace {
videoedit::VideoDecoder* asDecoder(jlong h) {
    return reinterpret_cast<videoedit::VideoDecoder*>(h);
}
} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoEditor_nativeProbe(
        JNIEnv* env, jobject /*thiz*/) {
    char buf[256];
    snprintf(buf, sizeof(buf),
             "videoedit native v0.1 — FFmpeg avformat=%u.%u.%u avcodec=%u.%u.%u avutil=%u.%u.%u",
             LIBAVFORMAT_VERSION_MAJOR, LIBAVFORMAT_VERSION_MINOR, LIBAVFORMAT_VERSION_MICRO,
             LIBAVCODEC_VERSION_MAJOR,  LIBAVCODEC_VERSION_MINOR,  LIBAVCODEC_VERSION_MICRO,
             LIBAVUTIL_VERSION_MAJOR,   LIBAVUTIL_VERSION_MINOR,   LIBAVUTIL_VERSION_MICRO);
    VE_LOGI("%s", buf);
    return env->NewStringUTF(buf);
}

// ---------------------------------------------------------------------------
// VideoDecoder bridge.
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeCreate(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new videoedit::VideoDecoder());
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete asDecoder(handle);
}

JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeOpen(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jpath) {
    auto* d = asDecoder(handle);
    if (!d || !jpath) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(jpath, nullptr);
    bool ok = d->open(utf ? utf : "");
    env->ReleaseStringUTFChars(jpath, utf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeOpenFd(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint fd) {
    auto* d = asDecoder(handle);
    if (!d) return JNI_FALSE;
    return d->openFd(fd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeClose(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (auto* d = asDecoder(handle)) d->close();
}

// Returns int[] = { width, height, durationMs, fps*100, rotation, hasAudio }.
// We pack into ints to avoid round-tripping a struct definition through JNI on
// every metadata fetch — call site is rare (once per video open).
JNIEXPORT jintArray JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeMetadata(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* d = asDecoder(handle);
    jintArray arr = env->NewIntArray(6);
    if (!d || !d->isOpen()) {
        jint zeros[6] = {0, 0, 0, 0, 0, 0};
        env->SetIntArrayRegion(arr, 0, 6, zeros);
        return arr;
    }
    const auto& m = d->metadata();
    jint values[6] = {
        m.width,
        m.height,
        static_cast<jint>(m.durationUs / 1000),
        static_cast<jint>(m.fps * 100.0),
        m.rotation,
        m.hasAudio ? 1 : 0,
    };
    env->SetIntArrayRegion(arr, 0, 6, values);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeSeekTo(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong timeUs) {
    auto* d = asDecoder(handle);
    return (d && d->seekTo(timeUs)) ? JNI_TRUE : JNI_FALSE;
}

// Decode a frame into the supplied (RGBA_8888) bitmap. Bitmap dimensions must
// match metadata.width/height — we don't scale here; if the caller wants to
// down-sample, they should target a smaller bitmap-render with sws separately.
// Returns: 1 = frame, 0 = EOF, -1 = error. PTS is read back via int[1] outPts.
JNIEXPORT jint JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoDecoder_nativeDecodeFrame(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject bitmap, jlongArray outPts) {
    auto* d = asDecoder(handle);
    if (!d || !d->isOpen() || !bitmap) return -1;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return -1;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1;
    if (static_cast<int>(info.width)  != d->metadata().width ||
        static_cast<int>(info.height) != d->metadata().height) {
        VE_LOGE("Bitmap size %ux%u doesn't match video %dx%d",
                info.width, info.height, d->metadata().width, d->metadata().height);
        return -1;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return -1;

    int64_t ptsUs = AV_NOPTS_VALUE;
    int result = d->decodeNextFrame(static_cast<uint8_t*>(pixels), &ptsUs);
    AndroidBitmap_unlockPixels(env, bitmap);

    if (outPts != nullptr && env->GetArrayLength(outPts) >= 1) {
        jlong v = ptsUs;
        env->SetLongArrayRegion(outPts, 0, 1, &v);
    }
    return result;
}

// ---------------------------------------------------------------------------
// VideoEditor (full pipeline) bridge.
// ---------------------------------------------------------------------------

namespace {
struct ProgressBridge {
    JNIEnv*     env;
    jobject     listener;
    jmethodID   onProgressId;
};
void progressTrampoline(float fraction, void* ctx) {
    auto* bridge = static_cast<ProgressBridge*>(ctx);
    if (!bridge || !bridge->listener || !bridge->onProgressId) return;
    bridge->env->CallVoidMethod(bridge->listener, bridge->onProgressId,
                                static_cast<jfloat>(fraction));
}
} // namespace

// Synchronous edit. `params` packed as float[10]:
//   [0..1]  trimStartMs, trimEndMs (both ms, -1 = unset)
//   [2..5]  cropX, cropY, cropW, cropH
//   [6..7]  outputWidth, outputHeight (0 = auto)
//   [8]     bitrate kbps
//   [9]     frameRate; +sign indicates keepAudio (negative = mute)
JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeVideoEditor_nativeProcess(
        JNIEnv* env, jobject /*thiz*/,
        jstring jInput, jstring jOutput, jfloatArray jParams, jobject listener) {
    if (!jInput || !jOutput) return JNI_FALSE;
    const char* inputPath  = env->GetStringUTFChars(jInput,  nullptr);
    const char* outputPath = env->GetStringUTFChars(jOutput, nullptr);
    if (!inputPath || !outputPath) return JNI_FALSE;

    videoedit::EditParams p;
    if (jParams && env->GetArrayLength(jParams) >= 15) {
        jfloat* a = env->GetFloatArrayElements(jParams, nullptr);
        if (a) {
            const float trimStartMs = a[0];
            const float trimEndMs   = a[1];
            p.trimStartUs = trimStartMs >= 0.f ? static_cast<int64_t>(trimStartMs * 1000.f) : 0;
            p.trimEndUs   = trimEndMs   >  0.f ? static_cast<int64_t>(trimEndMs   * 1000.f) : -1;
            p.cropX = a[2]; p.cropY = a[3]; p.cropW = a[4]; p.cropH = a[5];
            p.outputWidth  = static_cast<int>(a[6]);
            p.outputHeight = static_cast<int>(a[7]);
            p.bitrateBps   = static_cast<int>(a[8] * 1000.f);
            p.frameRate    = static_cast<int>(std::abs(a[9]));
            p.keepAudio    = a[9] >= 0.f;
            // Filters (indices 10..14)
            p.filters.exposure   = a[10];
            p.filters.brightness = a[11];
            p.filters.contrast   = a[12];
            p.filters.saturation = a[13];
            p.filters.warmth     = a[14];
            env->ReleaseFloatArrayElements(jParams, a, JNI_ABORT);
        }
    }

    ProgressBridge bridge{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        bridge.onProgressId = env->GetMethodID(cls, "onProgress", "(F)V");
    }

    videoedit::VideoEditor editor;
    bool ok = editor.process(inputPath, outputPath, p,
                             listener ? progressTrampoline : nullptr,
                             listener ? &bridge : nullptr);

    env->ReleaseStringUTFChars(jInput,  inputPath);
    env->ReleaseStringUTFChars(jOutput, outputPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// FFmpeg stream-copy trim (`ffmpeg -ss A -to B -i in -c copy out` equivalent).
// Returns a long[8]:
//   [0]  success (1/0)
//   [1]  bytesWritten
//   [2]  packetsWritten
//   [3]  firstPtsUs
//   [4]  lastPtsUs
//   [5..7] reserved
// Error message (if any) is available via nativeStreamCopyTrimLastError().
// ---------------------------------------------------------------------------

namespace {
// Last error message from streamCopyTrim — stashed across the JNI boundary so we
// don't need to allocate a Java String inside the worker.
std::string g_lastStreamCopyError;
} // namespace

JNIEXPORT jlongArray JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeStreamCopyTrim_nativeStreamCopyTrim(
        JNIEnv* env, jobject /*thiz*/,
        jstring jInput, jstring jOutput,
        jlong startUs, jlong endUs,
        jobject listener) {
    g_lastStreamCopyError.clear();
    if (!jInput || !jOutput) {
        g_lastStreamCopyError = "null input/output path";
        jlongArray ret = env->NewLongArray(8);
        return ret;
    }
    const char* inputPath  = env->GetStringUTFChars(jInput,  nullptr);
    const char* outputPath = env->GetStringUTFChars(jOutput, nullptr);

    // Progress callback bridge: invoke the Java listener's onProgress(float).
    struct ProgressBridge {
        JNIEnv*   env;
        jobject   listener;
        jmethodID method;
    } bridge{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        bridge.method = env->GetMethodID(cls, "onProgress", "(F)V");
    }
    videoedit::TrimProgressCallback cb;
    if (listener && bridge.method) {
        cb = [&bridge](float f) {
            bridge.env->CallVoidMethod(bridge.listener, bridge.method, (jfloat)f);
        };
    }

    videoedit::StreamCopyTrimResult r = videoedit::streamCopyTrim(
            inputPath, outputPath, (int64_t)startUs, (int64_t)endUs, cb);
    if (!r.success) g_lastStreamCopyError = r.error;

    env->ReleaseStringUTFChars(jInput,  inputPath);
    env->ReleaseStringUTFChars(jOutput, outputPath);

    jlongArray arr = env->NewLongArray(8);
    jlong values[8] = {
            r.success ? 1L : 0L,
            (jlong)r.bytesWritten,
            (jlong)r.packetsWritten,
            (jlong)r.firstPtsUs,
            (jlong)r.lastPtsUs,
            0, 0, 0,
    };
    env->SetLongArrayRegion(arr, 0, 8, values);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_io_element_android_libraries_videoeditor_native_1_NativeStreamCopyTrim_nativeStreamCopyTrimLastError(
        JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_lastStreamCopyError.c_str());
}

} // extern "C"
