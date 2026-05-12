// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// JNI entry points. Each `nativeXxx` Kotlin call lands here. The handle pattern
// (jlong → opaque pointer) lets the Kotlin side hold a single field instead of
// reaching into C++ struct layout.
#include <jni.h>

#include "BitmapHelper.h"
#include "../PhotoEditor.h"
#include "../core/Logger.h"
#include "../crop/CropParams.h"
#include "../paint/Brush.h"
#include "../paint/PaintEngine.h"
#include "../text/TextLayer.h"

namespace {

photoedit::PhotoEditor* asEditor(jlong handle) {
    return reinterpret_cast<photoedit::PhotoEditor*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeCreate(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* editor = new photoedit::PhotoEditor();
    if (!editor->init()) {
        PE_LOGE("nativeCreate: init failed");
        delete editor;
        return 0;
    }
    return reinterpret_cast<jlong>(editor);
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (auto* e = asEditor(handle)) {
        e->release();
        delete e;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeSetSourceBitmap(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject bitmap) {
    auto* e = asEditor(handle);
    if (!e || bitmap == nullptr) return JNI_FALSE;
    if (!e->makeContextCurrent()) return JNI_FALSE;
    photoedit::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) return JNI_FALSE;
    return e->setSourceBitmap(locked.pixels(), locked.width(), locked.height())
            ? JNI_TRUE : JNI_FALSE;
}

// Renders the source through the (Phase 1: empty) filter chain into the supplied
// destination bitmap. Output bitmap dimensions don't have to match the source —
// the renderer scales via FBO viewport.
JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeExportToBitmap(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject dstBitmap) {
    auto* e = asEditor(handle);
    if (!e || dstBitmap == nullptr) return JNI_FALSE;
    if (!e->makeContextCurrent()) return JNI_FALSE;
    photoedit::LockedBitmap locked(env, dstBitmap);
    if (!locked.isValid()) return JNI_FALSE;
    return e->exportToBitmap(locked.pixels(), locked.width(), locked.height())
            ? JNI_TRUE : JNI_FALSE;
}

// Sanity check — Kotlin calls this in NativePhotoEditor's static init to surface
// any "library failed to load" failure as a clear log line instead of a runtime
// crash later when the first real method is invoked.
JNIEXPORT jstring JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeHello(
        JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("photoedit native v0.2 (Phase 2: filters)");
}

// FilterParams float layout (must match NativePhotoEditor.kt FilterParams.toFloatArray):
//   [0] exposure   [1] brightness  [2] contrast    [3] saturation
//   [4] warmth     [5] fade        [6] highlights  [7] shadows
//   [8] vignette   [9] grain       [10] sharpen
//   [11..14] tintShadows RGBA      [15..18] tintHighlights RGBA
// Total: 19 scalars. Curves LUT goes through a separate jfloatArray param.
JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeSetFilterParams(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jfloatArray scalars, jfloatArray curveLut, jboolean curveIsIdentity) {
    auto* e = asEditor(handle);
    if (!e || scalars == nullptr) return;

    jfloat* s = env->GetFloatArrayElements(scalars, nullptr);
    if (!s) return;
    photoedit::FilterParams p;
    p.exposure   = s[0];
    p.brightness = s[1];
    p.contrast   = s[2];
    p.saturation = s[3];
    p.warmth     = s[4];
    p.fade       = s[5];
    p.highlights = s[6];
    p.shadows    = s[7];
    p.vignette   = s[8];
    p.grain      = s[9];
    p.sharpen    = s[10];
    for (int i = 0; i < 4; ++i) p.tintShadows[i]    = s[11 + i];
    for (int i = 0; i < 4; ++i) p.tintHighlights[i] = s[15 + i];
    // Indices 19..25 — blur params.
    p.blurType        = static_cast<int>(s[19]);
    p.blurCenterX     = s[20];
    p.blurCenterY     = s[21];
    p.blurInnerRadius = s[22];
    p.blurOuterRadius = s[23];
    p.blurAngle       = s[24];
    p.blurStrength    = s[25];
    env->ReleaseFloatArrayElements(scalars, s, JNI_ABORT);

    // Curves LUT: a single 1024-element float array (256 R + 256 G + 256 B + 256 luma).
    // Skipped if `curveIsIdentity` — saves the JNI copy + LUT upload on every call.
    p.curves.isIdentity = (curveIsIdentity == JNI_TRUE);
    if (curveLut != nullptr && !p.curves.isIdentity) {
        jsize len = env->GetArrayLength(curveLut);
        if (len == 1024) {
            jfloat* lut = env->GetFloatArrayElements(curveLut, nullptr);
            if (lut) {
                for (int i = 0; i < 256; ++i) p.curves.r[i]    = lut[i];
                for (int i = 0; i < 256; ++i) p.curves.g[i]    = lut[256 + i];
                for (int i = 0; i < 256; ++i) p.curves.b[i]    = lut[512 + i];
                for (int i = 0; i < 256; ++i) p.curves.luma[i] = lut[768 + i];
                env->ReleaseFloatArrayElements(curveLut, lut, JNI_ABORT);
            }
        }
    }

    if (e->makeContextCurrent()) {
        e->setFilterParams(p);
    }
}

// CropParams float layout (Kotlin: CropParams.toFloatArray):
//   [0] x [1] y [2] w [3] h [4] rotation90 [5] freeAngle [6] mirrorH [7] mirrorV
JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeSetCropParams(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray packed) {
    auto* e = asEditor(handle);
    if (!e || packed == nullptr) return;
    jfloat* s = env->GetFloatArrayElements(packed, nullptr);
    if (!s) return;
    photoedit::CropParams p;
    p.x          = s[0];
    p.y          = s[1];
    p.w          = s[2];
    p.h          = s[3];
    p.rotation90 = static_cast<int>(s[4]);
    p.freeAngle  = s[5];
    p.mirrorH    = s[6] != 0.f;
    p.mirrorV    = s[7] != 0.f;
    env->ReleaseFloatArrayElements(packed, s, JNI_ABORT);
    if (e->makeContextCurrent()) e->setCropParams(p);
}

// Paint API. Brush params packed as a float[8]:
//   [0] type (BrushType ordinal as float)
//   [1..4] colour RGBA  [5] radiusPx  [6] hardness
JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeBeginStroke(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jfloatArray brushPacked, jfloat x, jfloat y, jfloat pressure) {
    auto* e = asEditor(handle);
    if (!e || brushPacked == nullptr) return;
    if (!e->makeContextCurrent()) return;
    auto* paint = e->paint();
    if (!paint) return;
    jfloat* b = env->GetFloatArrayElements(brushPacked, nullptr);
    if (!b) return;
    photoedit::BrushParams bp;
    bp.type     = static_cast<photoedit::BrushType>(static_cast<int>(b[0]));
    bp.r        = b[1];
    bp.g        = b[2];
    bp.b        = b[3];
    bp.a        = b[4];
    bp.radiusPx = b[5];
    bp.hardness = b[6];
    env->ReleaseFloatArrayElements(brushPacked, b, JNI_ABORT);

    photoedit::StrokePoint sp{x, y, pressure};
    paint->beginStroke(bp, sp);
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeExtendStroke(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
        jfloat x, jfloat y, jfloat pressure) {
    auto* e = asEditor(handle);
    if (!e || !e->makeContextCurrent()) return;
    auto* paint = e->paint();
    if (!paint) return;
    paint->extendStroke({x, y, pressure});
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeEndStroke(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (!e || !e->makeContextCurrent()) return;
    auto* paint = e->paint();
    if (paint) paint->endStroke();
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeUndoPaint(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->paint()) e->paint()->undoPaintLayer();
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeRedoPaint(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->paint()) e->paint()->redoPaintLayer();
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeUndoBlur(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->paint()) e->paint()->undoBlurLayer();
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeRedoBlur(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->paint()) e->paint()->redoBlurLayer();
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeClearPaint(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->paint()) e->paint()->clear();
}

// Text API. The bitmap is rendered by Kotlin (StaticLayout / Canvas.drawText);
// we just upload it as a texture and remember the transform.
JNIEXPORT jboolean JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeUpsertTextItem(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jint id, jobject bitmap,
        jfloat x, jfloat y, jfloat scale, jfloat rotationRad) {
    auto* e = asEditor(handle);
    if (!e || bitmap == nullptr) return JNI_FALSE;
    if (!e->makeContextCurrent() || !e->text()) return JNI_FALSE;
    photoedit::LockedBitmap locked(env, bitmap);
    if (!locked.isValid()) return JNI_FALSE;
    return e->text()->upsert(id, locked.pixels(), locked.width(), locked.height(),
                             x, y, scale, rotationRad)
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeRemoveTextItem(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint id) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->text()) e->text()->remove(id);
}

JNIEXPORT void JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeClearText(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    if (e && e->makeContextCurrent() && e->text()) e->text()->clear();
}

// Returns a 2-element int array: [width, height] of the post-crop output.
// Useful for Kotlin to allocate a destination Bitmap of the right size before
// calling nativeExportToBitmap.
JNIEXPORT jintArray JNICALL
Java_io_element_android_libraries_imageeditor_native_1_NativePhotoEditor_nativeCroppedOutputSize(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* e = asEditor(handle);
    int w = 0, h = 0;
    if (e) e->croppedOutputSize(w, h);
    jintArray out = env->NewIntArray(2);
    jint values[2] = { w, h };
    env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

} // extern "C"
