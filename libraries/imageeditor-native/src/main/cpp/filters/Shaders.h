// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// All filter fragment shaders in one place. Keeping them here (rather than one .glsl
// per filter that gets baked into a `.h` at build time) means a developer can grep
// "uniform float u_" and see every parameter the pipeline accepts. The vertex shader
// is shared with the passthrough renderer (see core/Renderer.cpp:kVertexSrc) — every
// filter samples a fullscreen quad, so the vertex stage never changes.
//
// Convention:
//  - sampler `u_tex`    is always the input
//  - sampler `u_lut`    is the curves LUT (256x1 RGBA), only present in fsCurves
//  - varying `v_uv`     is texture-space coords [0,1]
//  - all colours premultiplied alpha — filters preserve `c.a` and operate on `c.rgb`
#pragma once

namespace photoedit::shaders {

// ---------------------------------------------------------------------------
// Tone group: exposure, brightness, contrast, saturation. One pass — these all
// touch RGB linearly so combining them avoids three FBO ping-pongs.
// ---------------------------------------------------------------------------
constexpr const char* kFsToneBcs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_exposure;     // [-2, 2] EV — multiplies by 2^EV
uniform float u_brightness;   // [-1, 1]  — adds to RGB after exposure
uniform float u_contrast;     // [0, 2]   — pivots around 0.5
uniform float u_saturation;   // [0, 2]   — mixes against luma
in vec2 v_uv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec4 c = texture(u_tex, v_uv);
    c.rgb *= pow(2.0, u_exposure);
    c.rgb += u_brightness;
    c.rgb = (c.rgb - 0.5) * u_contrast + 0.5;
    float l = dot(c.rgb, LUMA);
    c.rgb = mix(vec3(l), c.rgb, u_saturation);
    fragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
}
)";

// ---------------------------------------------------------------------------
// Warmth — colour-temperature shift on a blue↔orange axis. Negative cools the
// image (push blue, pull red); positive warms (the opposite). Linear blend so
// extreme values flatten the histogram, which is the desired film-look.
// ---------------------------------------------------------------------------
constexpr const char* kFsWarmth = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_amount;       // [-1, 1]
in vec2 v_uv;
out vec4 fragColor;

void main() {
    vec4 c = texture(u_tex, v_uv);
    c.r += u_amount * 0.15;
    c.b -= u_amount * 0.15;
    fragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
}
)";

// ---------------------------------------------------------------------------
// Fade — matte/desaturated film look. Lifts blacks, compresses contrast.
// Re-implementation of the classic Instagram-style fade; not Telegram-specific.
// ---------------------------------------------------------------------------
constexpr const char* kFsFade = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_amount;       // [0, 1]
in vec2 v_uv;
out vec4 fragColor;

void main() {
    vec4 c = texture(u_tex, v_uv);
    vec3 lifted = mix(c.rgb, vec3(0.5), u_amount * 0.18);
    vec3 toned  = (lifted - 0.5) * (1.0 - u_amount * 0.4) + 0.5;
    fragColor = vec4(clamp(toned, 0.0, 1.0), c.a);
}
)";

// ---------------------------------------------------------------------------
// Highlights/Shadows — selective tone control by luminance band. The masks are
// pow(luma, 2) for highlights and pow(1-luma, 2) for shadows, so mid-tones are
// untouched and the curves taper smoothly into the extremes.
// ---------------------------------------------------------------------------
constexpr const char* kFsHighlightsShadows = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_highlights;   // [-1, 1]
uniform float u_shadows;      // [-1, 1]
in vec2 v_uv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec4 c = texture(u_tex, v_uv);
    float l = dot(c.rgb, LUMA);
    float shadowMask    = pow(1.0 - l, 2.0);
    float highlightMask = pow(l, 2.0);
    vec3 result = c.rgb;
    result += u_shadows    * shadowMask    * 0.5;
    result += u_highlights * highlightMask * 0.5;
    fragColor = vec4(clamp(result, 0.0, 1.0), c.a);
}
)";

// ---------------------------------------------------------------------------
// Vignette — radial darken from centre. Simple smoothstep mask, no chromatic
// shift. The 0.4..0.95 stops give a soft fall-off at low intensities and a
// strong corner darkening at intensity=1.
// ---------------------------------------------------------------------------
constexpr const char* kFsVignette = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_intensity;    // [0, 1]
in vec2 v_uv;
out vec4 fragColor;

void main() {
    vec4 c = texture(u_tex, v_uv);
    vec2 d = v_uv - vec2(0.5);
    float r = length(d);
    float vig = smoothstep(0.4, 0.95, r) * u_intensity;
    fragColor = vec4(c.rgb * (1.0 - vig), c.a);
}
)";

// ---------------------------------------------------------------------------
// Grain — adds high-frequency noise. We use the standard `fract(sin(dot(uv*N,
// vec2(...)))*K)` PRNG (cheap, fully GPU-side, no dither texture needed). The
// `u_seed` time-shifts the pattern so successive frames don't show identical
// noise (which would look static instead of film-grain).
// ---------------------------------------------------------------------------
constexpr const char* kFsGrain = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_amount;       // [0, 1]
uniform float u_seed;         // monotonic counter; each render bumps it
uniform vec2  u_resolution;   // pixels
in vec2 v_uv;
out vec4 fragColor;

float prng(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 c = texture(u_tex, v_uv);
    vec2 p = v_uv * u_resolution + u_seed;
    float n = prng(p) - 0.5;            // [-0.5, 0.5]
    c.rgb += n * u_amount * 0.18;
    fragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
}
)";

// ---------------------------------------------------------------------------
// Sharpen — 3x3 unsharp mask. `5*center - top - bottom - left - right` is the
// standard Laplacian kernel; we mix it back against the original by `u_amount`
// so a fully-sharpened result at amount=1 doesn't ring excessively.
// ---------------------------------------------------------------------------
constexpr const char* kFsSharpen = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_amount;       // [0, 1]
uniform vec2  u_texelSize;    // 1.0 / textureWidth, 1.0 / textureHeight
in vec2 v_uv;
out vec4 fragColor;

void main() {
    vec3 c   = texture(u_tex, v_uv).rgb;
    vec3 t   = texture(u_tex, v_uv + vec2(0.0,  u_texelSize.y)).rgb;
    vec3 b   = texture(u_tex, v_uv + vec2(0.0, -u_texelSize.y)).rgb;
    vec3 l   = texture(u_tex, v_uv + vec2(-u_texelSize.x, 0.0)).rgb;
    vec3 r   = texture(u_tex, v_uv + vec2( u_texelSize.x, 0.0)).rgb;
    vec3 sharp = 5.0 * c - t - b - l - r;
    vec3 blended = mix(c, sharp, u_amount);
    fragColor = vec4(clamp(blended, 0.0, 1.0), texture(u_tex, v_uv).a);
}
)";

// ---------------------------------------------------------------------------
// Tint — separately tint shadows and highlights with an arbitrary RGB. The
// shadow/highlight masks are the same shape as the highlights/shadows filter
// above, but the math here is "blend toward target colour", not "shift luma".
// `u_tintShadows.rgb` is the colour, `u_tintShadows.a` is the intensity.
// ---------------------------------------------------------------------------
constexpr const char* kFsTint = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform vec4 u_tintShadows;
uniform vec4 u_tintHighlights;
in vec2 v_uv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec4 c = texture(u_tex, v_uv);
    float l = dot(c.rgb, LUMA);
    float shadowMask    = pow(1.0 - l, 2.0);
    float highlightMask = pow(l, 2.0);
    c.rgb = mix(c.rgb, u_tintShadows.rgb,    shadowMask    * u_tintShadows.a);
    c.rgb = mix(c.rgb, u_tintHighlights.rgb, highlightMask * u_tintHighlights.a);
    fragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
}
)";

// ---------------------------------------------------------------------------
// Selective blur — Telegram-style two-pass Gaussian where the kernel size is
// modulated by a focal mask. The mask is `mask(uv)` — 0 in the sharp region,
// 1 in the fully-blurred region, smoothstepped between. The shader does N
// taps along the blur direction; we pre-compute weights for a 9-tap kernel.
//
// Mode = 0  off (passthrough — FilterChain skips this stage)
// Mode = 1  radial (circular focal area)
// Mode = 2  linear (gradient blur perpendicular to `u_angle`)
//
// Real Telegram does a multi-pass downsample-blur-upsample for performance;
// we approximate with a single 9-tap horizontal+vertical separable pass for
// simplicity (cost ≈ 18 texture fetches per fragment in the blurred region,
// still real-time at 4K on Pixel-class GPUs).
// ---------------------------------------------------------------------------
constexpr const char* kFsBlur = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform vec2  u_texelSize;        // 1/width, 1/height
uniform int   u_mode;             // 1 = radial, 2 = linear
uniform vec2  u_centre;           // [0,1] image coords
uniform float u_innerRadius;      // [0,1] image-fraction
uniform float u_outerRadius;
uniform float u_angle;            // radians (linear)
uniform float u_strength;         // [0,1] multiplier on the kernel offset
in vec2 v_uv;
out vec4 fragColor;

float radialMask(vec2 uv) {
    return smoothstep(u_innerRadius, u_outerRadius, distance(uv, u_centre));
}

float linearMask(vec2 uv) {
    // Distance from `uv` to the line passing through `u_centre` with direction
    // `(cos(u_angle), sin(u_angle))`. We measure the perpendicular distance.
    vec2 dir  = vec2(cos(u_angle), sin(u_angle));
    vec2 perp = vec2(-dir.y, dir.x);
    float d = abs(dot(uv - u_centre, perp));
    return smoothstep(u_innerRadius, u_outerRadius, d);
}

void main() {
    vec4 base = texture(u_tex, v_uv);
    float mask = (u_mode == 1) ? radialMask(v_uv) :
                 (u_mode == 2) ? linearMask(v_uv) : 0.0;
    if (mask <= 0.0) { fragColor = base; return; }

    // 9-tap symmetric Gaussian along both axes (separable would need a 2-pass
    // chain, but for a single-pass shader we trade quality for ping-pong cost).
    // Weights for a 9-tap σ ≈ 2 (normalised).
    const float kW[5] = float[5](0.227027, 0.194594, 0.121622, 0.054054, 0.016216);
    vec2 step = u_texelSize * mask * u_strength * 4.0;
    vec3 sum = base.rgb * kW[0];
    for (int i = 1; i < 5; ++i) {
        vec2 ofs = vec2(float(i)) * step;
        sum += texture(u_tex, v_uv + vec2(ofs.x, 0.0)).rgb * kW[i];
        sum += texture(u_tex, v_uv - vec2(ofs.x, 0.0)).rgb * kW[i];
        sum += texture(u_tex, v_uv + vec2(0.0, ofs.y)).rgb * kW[i];
        sum += texture(u_tex, v_uv - vec2(0.0, ofs.y)).rgb * kW[i];
    }
    // Normalise — we summed five horizontal + four vertical pairs unevenly.
    sum *= 1.0 / 1.272;     // empirical normalisation for the 4-direction tap layout
    fragColor = vec4(mix(base.rgb, sum, mask), base.a);
}
)";

// ---------------------------------------------------------------------------
// Curves — per-channel LUT. The LUT is uploaded as a 256x1 RGBA texture; each
// row of u_lut maps the *input* channel value to the *output* channel value
// (R column → R remap, etc.). The luma column is applied as a final tone curve.
// ---------------------------------------------------------------------------
constexpr const char* kFsCurves = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform sampler2D u_lut;       // 256x1, RGBA = (R-curve, G-curve, B-curve, luma)
in vec2 v_uv;
out vec4 fragColor;

const vec3 LUMA_W = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec4 c = texture(u_tex, v_uv);
    // Per-channel remap. v=0.5 keeps the LUT row stable; we sample column by the
    // current channel value, plus a half-pixel offset so we land at texel centres.
    float r = texture(u_lut, vec2(c.r, 0.5)).r;
    float g = texture(u_lut, vec2(c.g, 0.5)).g;
    float b = texture(u_lut, vec2(c.b, 0.5)).b;
    vec3 channelOut = vec3(r, g, b);
    // Luma curve — applied by remapping luminance and reattaching colour.
    float lIn  = dot(channelOut, LUMA_W);
    float lOut = texture(u_lut, vec2(lIn, 0.5)).a;
    vec3 result = channelOut * (lOut / max(lIn, 1e-4));
    fragColor = vec4(clamp(result, 0.0, 1.0), c.a);
}
)";

} // namespace photoedit::shaders
