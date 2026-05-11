/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_

/**
 * Photo-filter parameters. Default-constructed value is the identity (no filter applied).
 *
 * Layout matches `photoedit-jni.cpp::nativeSetFilterParams` — the C++ side reads scalars by
 * index, so reordering fields here is a binary-incompatible change. If you add a parameter,
 * append it to [toFloatArray] AND extend the C++ unmarshalling switch.
 */
data class FilterParams(
    val exposure: Float = 0f,        // [-2, 2] EV
    val brightness: Float = 0f,      // [-1, 1]
    val contrast: Float = 1f,        // [0, 2]
    val saturation: Float = 1f,      // [0, 2]
    val warmth: Float = 0f,          // [-1, 1]
    val fade: Float = 0f,            // [0, 1]
    val highlights: Float = 0f,      // [-1, 1]
    val shadows: Float = 0f,         // [-1, 1]
    val vignette: Float = 0f,        // [0, 1]
    val grain: Float = 0f,           // [0, 1]
    val sharpen: Float = 0f,         // [0, 1]
    val tintShadowsR: Float = 0f,
    val tintShadowsG: Float = 0f,
    val tintShadowsB: Float = 0f,
    val tintShadowsA: Float = 0f,    // 0 = no tint
    val tintHighlightsR: Float = 0f,
    val tintHighlightsG: Float = 0f,
    val tintHighlightsB: Float = 0f,
    val tintHighlightsA: Float = 0f,
    /** Selective focus blur — Telegram-style. Off by default. */
    val blur: BlurParams = BlurParams(),
    val curves: CurveLut = CurveLut(),
) {
    /** 26 scalars matching the C++ `FilterParams` layout (curves go through [curves]). */
    fun toFloatArray(): FloatArray = floatArrayOf(
        exposure, brightness, contrast, saturation,
        warmth, fade, highlights, shadows,
        vignette, grain, sharpen,
        tintShadowsR, tintShadowsG, tintShadowsB, tintShadowsA,
        tintHighlightsR, tintHighlightsG, tintHighlightsB, tintHighlightsA,
        blur.type.modeForJni.toFloat(),
        blur.centerX, blur.centerY,
        blur.innerRadius, blur.outerRadius,
        blur.angleRadians, blur.strength,
    )
}

/** Selective-focus blur (Telegram-style). [BlurType.Off] = passthrough. */
enum class BlurType(val modeForJni: Int) {
    Off(0),
    Radial(1),
    Linear(2),
}

data class BlurParams(
    val type: BlurType = BlurType.Off,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val innerRadius: Float = 0.15f,
    val outerRadius: Float = 0.35f,
    val angleRadians: Float = 0f,
    val strength: Float = 0.6f,
)

/**
 * 256-entry per-channel curves LUT. Identity is a linear ramp `t/255 = t/255` on each channel.
 * Custom curves: build a [FloatArray] of length 1024 — first 256 = R, next 256 = G, then B,
 * then luma. Each value in [0, 1].
 */
data class CurveLut(
    val data: FloatArray = identityRamp(),
    val isIdentity: Boolean = true,
) {
    init { require(data.size == 1024) { "CurveLut data must be 1024 floats (256 × 4)" } }

    companion object {
        fun identityRamp(): FloatArray {
            val out = FloatArray(1024)
            for (i in 0 until 256) {
                val v = i / 255f
                out[i] = v
                out[256 + i] = v
                out[512 + i] = v
                out[768 + i] = v
            }
            return out
        }
    }

    // data class equality on FloatArray would compare references; provide a content-aware check.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CurveLut) return false
        return isIdentity == other.isIdentity && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = 31 * isIdentity.hashCode() + data.contentHashCode()
}
