/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_

/**
 * Brush types — must match the C++ `BrushType` enum ordinals exactly. Reordering
 * either side without the other breaks the JNI contract.
 */
enum class BrushType(val ordinalForJni: Int) {
    Pen(0),
    Marker(1),
    Neon(2),
    Arrow(3),
    Eraser(4),

    /**
     * Blur brush — stamps a soft mask into a dedicated FBO on the native side.
     * The renderer's reveal pass then mixes a pre-blurred copy of the source
     * into the composed image wherever that mask is opaque. Per-stroke colour
     * is ignored (the mask is colour-agnostic); only [Brush.radiusPx] matters.
     */
    BlurBrush(5),
}

data class Brush(
    val type: BrushType = BrushType.Pen,
    val r: Float = 1f,
    val g: Float = 1f,
    val b: Float = 1f,
    val a: Float = 1f,
    /** Stamp radius in *destination pixel space* — i.e. paint-layer pixels, not dp. */
    val radiusPx: Float = 8f,
    /** Edge falloff for [BrushType.Pen] / [BrushType.Eraser]. 1.0 = sharp edge, 0.0 = soft.
     *  Default kept high so a baked native pen stroke matches the Compose live-overlay
     *  Path drawing (anti-aliased solid line, virtually no fade). Lower it for soft brushes. */
    val hardness: Float = 0.92f,
) {
    /** Layout matches `nativeBeginStroke`'s float[8] in photoedit-jni.cpp. */
    fun toFloatArray(): FloatArray = floatArrayOf(
        type.ordinalForJni.toFloat(),
        r, g, b, a,
        radiusPx,
        hardness,
        0f,    // reserved
    )
}
