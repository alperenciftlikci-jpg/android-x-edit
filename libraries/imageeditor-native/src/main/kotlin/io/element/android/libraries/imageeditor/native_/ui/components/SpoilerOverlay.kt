/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Telegram-style spoiler dust effect — particle field that drifts over its container.
 *
 * Pairs with [SpoileredImage] which supplies the Telegram-faithful blurred backdrop
 * underneath. The constants here mirror what `SpoilerEffect2.java` / the media-spoiler
 * path in Telegram-Android use; we deliberately do NOT paint a dark scrim — the
 * downscale-saturated backdrop already hides the photo content, and a scrim flattens
 * the colour signature Telegram lets through (the backdrop's hint of dominant hue is
 * what makes a spoiler look "alive" instead of like a blackout sticker).
 *
 * Particle count and reveal timing both scale with the rendered size: matching the
 * formulas in Telegram's `SpoilerEffect2` (`w*h/(500*500)*1000`, clamp 500–10000) and
 * `ChatMessageCell.startRevealMedia` (`clamp(diagonal * 0.3f, 250, 550)` ms with
 * `CubicBezierInterpolator.EASE_BOTH`).
 *
 * @param revealable when `false`, tap handling is disabled — the dust never lifts
 *                   (editor / pre-send preview, where the author is authoring).
 * @param onRevealed fired exactly once when the reveal animation finishes.
 * @param particleColor dust colour. Telegram uses opaque white.
 */
@Composable
fun SpoilerOverlay(
    modifier: Modifier = Modifier,
    revealable: Boolean = true,
    onRevealed: () -> Unit = {},
    particleColor: Color = Color.White,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    // Particles are lazily allocated once we know the laid-out size — count is
    // area-proportional per the SpoilerEffect2 formula, then clamped to a sane range.
    var particles by remember { mutableStateOf<Array<Particle>?>(null) }
    val random = remember { Random(System.nanoTime()) }
    var tick by remember { mutableStateOf(0L) }

    val revealProgress = remember { Animatable(0f) }
    var revealCenter by remember { mutableStateOf<Offset?>(null) }
    var revealedFired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 1.2 dp matches Telegram's particle radius (SpoilerEffect2:315). Converting to px
    // once here keeps the per-frame loop free of toPx() conversions.
    val dotPx = with(density) { 1.2.dp.toPx() }

    // Per-frame allocations are the dominant cost in the reveal animation. The previous
    // Compose `drawPoints(List<Offset>)` path allocated three lists + N Offsets every
    // frame; even the hoisted-ArrayList version still allocated one Offset per particle
    // per frame. The user reported visible jank during reveal, so we switch to native
    // `android.graphics.Canvas.drawPoints(FloatArray)` — single native call per bucket
    // with a packed float buffer reused across frames. Allocations per frame: 0.
    val pointBufferA = remember { FloatArray(1500 * 2) }
    val pointBufferB = remember { FloatArray(1500 * 2) }
    val pointBufferC = remember { FloatArray(1500 * 2) }
    val nativePaintA = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            strokeWidth = 0f  // set in draw block
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
        }
    }
    val nativePaintB = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            strokeWidth = 0f
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
        }
    }
    val nativePaintC = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            strokeWidth = 0f
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
        }
    }
    // Single reused Path for the reveal clip. Rebuilt every frame via rewind() + add*,
    // never allocated after the first frame.
    val clipPath = remember { Path() }

    LaunchedEffect(particles, canvasSize) {
        val pool = particles ?: return@LaunchedEffect
        if (canvasSize == Size.Zero) return@LaunchedEffect
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                val dt = if (lastFrameNs == 0L) 0f
                else ((frameNs - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrameNs = frameNs

                // Telegram applies a 0.65× time scale to the particle update
                // (SpoilerEffect2:517) — particles drift visibly slower than real time
                // so the field reads as "twinkling" instead of "swarming".
                val scaledDt = dt * 0.65f
                for (p in pool) {
                    p.age += scaledDt
                    if (p.age >= p.lifetime) {
                        spawn(p, canvasSize.width, canvasSize.height, random)
                    }
                    p.x += p.vx * scaledDt
                    p.y += p.vy * scaledDt
                }
                tick = frameNs
            }
        }
    }

    LaunchedEffect(revealProgress.value) {
        if (revealProgress.value >= 1f && !revealedFired) {
            revealedFired = true
            onRevealed()
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { sz ->
                if (sz.width <= 0 || sz.height <= 0) return@onSizeChanged
                val w = sz.width.toFloat()
                val h = sz.height.toFloat()
                canvasSize = Size(w, h)
                if (particles == null) {
                    // Particle count formula from Telegram's SpoilerEffect2:326 (area /
                    // 500dp² × 1000). Telegram clamps to 10000 but they share ONE GL
                    // surface across all visible cells — one simulation, many readers.
                    // We compute per-cell on the CPU, so 10000 × N visible bubbles is
                    // unrealistic; we cap at 1500 instead. Visual loss is minimal because
                    // the human eye saturates on dust density well below that.
                    val count = ((w * h) / (500f * 500f) * 1000f)
                        .toInt()
                        .coerceIn(400, 1500)
                    val pool = Array(count) { Particle() }
                    for (p in pool) {
                        spawn(p, w, h, random)
                        p.age = random.nextFloat() * p.lifetime
                    }
                    particles = pool
                }
            }
            .then(
                if (revealable) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { tap ->
                            if (revealCenter == null) {
                                revealCenter = tap
                                // Reveal duration scales with the image diagonal (in px),
                                // clamped 250-550 ms — matches the formula in
                                // ChatMessageCell.startRevealMedia:14903. Easing is the
                                // CubicBezierInterpolator.EASE_BOTH curve Telegram uses
                                // app-wide (0.42, 0, 0.58, 1).
                                val diag = hypot(
                                    canvasSize.width.toDouble(),
                                    canvasSize.height.toDouble(),
                                ).toFloat()
                                val durationMs = (diag * 0.3f).toInt().coerceIn(250, 550)
                                scope.launch {
                                    revealProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = durationMs,
                                            easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } else Modifier
            ),
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        val pool = particles ?: return@Canvas
        val w = size.width
        val h = size.height
        val center = revealCenter
        val progress = revealProgress.value

        // Reveal clip: full canvas minus a growing circle from the tap point. Telegram
        // expands the circle until it covers the whole image rect (radius = image
        // diagonal × progress) and uses Region.Op.DIFFERENCE — we replicate with
        // PathFillType.EvenOdd. clipPath is the hoisted Path, rewound each frame.
        clipPath.rewind()
        clipPath.fillType = PathFillType.EvenOdd
        clipPath.addRect(Rect(0f, 0f, w, h))
        if (center != null && progress > 0f) {
            val maxR = hypot(w.toDouble(), h.toDouble()).toFloat()
            val r = maxR * progress
            clipPath.addOval(Rect(
                left = center.x - r,
                top = center.y - r,
                right = center.x + r,
                bottom = center.y + r,
            ))
        }

        clipPath(clipPath) {
            // Bucket-by-alpha so we can batch into 3 native drawPoints calls. The
            // per-particle alpha variation reads as a "twinkle"; uniform alpha looks
            // mechanical. We write directly into pre-allocated FloatArrays (x, y, x, y,
            // …) and pass them to native `Canvas.drawPoints(pts, offset, count, paint)`
            // — single JNI call per bucket, zero per-frame allocation.
            val globalFade = 1f - progress
            var idxA = 0
            var idxB = 0
            var idxC = 0
            val capA = pointBufferA.size
            val capB = pointBufferB.size
            val capC = pointBufferC.size
            for (p in pool) {
                val life = (1f - p.age / p.lifetime).coerceIn(0f, 1f)
                val a = life * globalFade
                if (a <= 0.05f) continue
                when {
                    a > 0.66f -> if (idxA + 1 < capA) {
                        pointBufferA[idxA++] = p.x
                        pointBufferA[idxA++] = p.y
                    }
                    a > 0.33f -> if (idxB + 1 < capB) {
                        pointBufferB[idxB++] = p.x
                        pointBufferB[idxB++] = p.y
                    }
                    else -> if (idxC + 1 < capC) {
                        pointBufferC[idxC++] = p.x
                        pointBufferC[idxC++] = p.y
                    }
                }
            }
            // Update paint colours each frame (cheap — int assignment). Alpha encoded
            // into the int ARGB; alpha 1.0 → 0xFF, 0.65 → 0xA6, 0.30 → 0x4D.
            val argb = particleColor.toArgb()
            val baseRgb = argb and 0x00FFFFFF
            nativePaintA.color = (0xFF shl 24) or baseRgb
            nativePaintB.color = (0xA6 shl 24) or baseRgb
            nativePaintC.color = (0x4D shl 24) or baseRgb
            nativePaintA.strokeWidth = dotPx
            nativePaintB.strokeWidth = dotPx
            nativePaintC.strokeWidth = dotPx
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                if (idxA > 0) nc.drawPoints(pointBufferA, 0, idxA, nativePaintA)
                if (idxB > 0) nc.drawPoints(pointBufferB, 0, idxB, nativePaintB)
                if (idxC > 0) nc.drawPoints(pointBufferC, 0, idxC, nativePaintC)
            }
        }
    }
}

private class Particle(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var age: Float = 0f,
    var lifetime: Float = 1f,
)

private fun spawn(p: Particle, w: Float, h: Float, random: Random) {
    p.x = random.nextFloat() * w
    p.y = random.nextFloat() * h
    val angle = random.nextFloat() * (2f * Math.PI.toFloat())
    val speed = 6f + random.nextFloat() * 14f
    p.vx = cos(angle) * speed
    p.vy = sin(angle) * speed
    p.age = 0f
    p.lifetime = 0.6f + random.nextFloat() * 0.7f
}
