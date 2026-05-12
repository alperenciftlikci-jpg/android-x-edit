/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Telegram-style spoiler dust effect. Draws a field of tiny white dots that drift around;
 * an optional tap reveals the layer beneath via an expanding circular clip animated outward
 * from the tap point. Mirrors the visual feel of `SpoilerEffect.java` in DrKLO/Telegram's
 * Android client: white particles, soft falloff, ~1 s reveal with the particles outside
 * the reveal circle fading and accelerating away.
 *
 * Composable contract — caller stacks this on top of whatever it wants hidden (typically an
 * `Image`). Sizing is driven by the modifier; the effect captures its laid-out size on the
 * first layout pass and seeds the particle pool to fill that area uniformly.
 *
 * @param revealable when `false`, tap handling is disabled — the dust never lifts.
 *                   The editor preview uses `false` (the user is authoring, not consuming);
 *                   the chat bubble uses `true`.
 * @param onRevealed fired exactly once when the reveal animation finishes (progress = 1).
 *                   Caller typically responds by removing this overlay from the tree.
 * @param particleColor dust colour. Telegram uses white at ~75 % alpha — that combined with
 *                      a blurred backdrop is what sells the effect.
 * @param particleCount number of dots in the pool. Defaults to 250 — dense enough to look
 *                      like static fuzz, cheap enough to render in 3 `drawPoints` calls.
 */
@Composable
fun SpoilerOverlay(
    modifier: Modifier = Modifier,
    revealable: Boolean = true,
    onRevealed: () -> Unit = {},
    particleColor: Color = Color.White,
    particleCount: Int = 250,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val particles = remember { Array(particleCount) { Particle() } }
    val random = remember { Random(System.nanoTime()) }
    var initialized by remember { mutableStateOf(false) }

    // `tick` is read inside the Canvas content lambda — that's what tells Compose to
    // re-issue the draw call on every frame even though `particles` is a plain mutable
    // array (not snapshot state). Without this dependency the particles would update in
    // place but the screen would never redraw.
    var tick by remember { mutableStateOf(0L) }

    // Reveal state. `revealCenter` stays null until the first tap; once set it never
    // changes (a second tap during reveal is ignored — Telegram does the same).
    val revealProgress = remember { Animatable(0f) }
    var revealCenter by remember { mutableStateOf<Offset?>(null) }
    var revealedFired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Frame ticker. Re-keyed on `initialized` + `canvasSize` so the loop doesn't run
    // before we have anything to draw (avoids a wasted Choreographer hookup during the
    // single-frame window between layout and first paint).
    LaunchedEffect(initialized, canvasSize) {
        if (!initialized || canvasSize == Size.Zero) return@LaunchedEffect
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                val dt = if (lastFrameNs == 0L) 0f
                else ((frameNs - lastFrameNs) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrameNs = frameNs

                // During reveal: scale up particle velocity so they appear to "blow away"
                // from the tap point. Matches the multiplied-velocity field in
                // ChatMessageCell's reveal path.
                val velScale = 1f + revealProgress.value * 4f
                for (p in particles) {
                    p.age += dt
                    if (p.age >= p.lifetime) {
                        spawn(p, canvasSize.width, canvasSize.height, random)
                    }
                    p.x += p.vx * dt * velScale
                    p.y += p.vy * dt * velScale
                }
                tick = frameNs
            }
        }
    }

    // Fire `onRevealed` exactly once. revealProgress is an Animatable so its `value` is
    // observed by recomposition; gating with a local boolean avoids a re-fire if the
    // caller leaves the overlay mounted past completion.
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
                if (!initialized) {
                    // Seed the pool. Stagger ages so respawns happen at different times
                    // — otherwise every particle re-spawns at the same frame and the
                    // field visibly "pulses".
                    for (p in particles) {
                        spawn(p, w, h, random)
                        p.age = random.nextFloat() * p.lifetime
                    }
                    initialized = true
                }
            }
            .then(
                if (revealable) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { tap ->
                            if (revealCenter == null) {
                                revealCenter = tap
                                scope.launch {
                                    revealProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = 1200,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } else Modifier
            ),
    ) {
        // Read `tick` so Compose tracks per-frame dependency. Cheap no-op read.
        @Suppress("UNUSED_EXPRESSION") tick

        if (!initialized) return@Canvas

        val w = size.width
        val h = size.height
        val center = revealCenter
        val progress = revealProgress.value

        // Build the clip path: full canvas rect MINUS a circle centred on the tap point.
        // PathFillType.EvenOdd lets two sub-paths "cancel" — drawing inside the hole is
        // suppressed, drawing outside the hole runs normally. When `revealCenter` is
        // null we just clip to the full rect (no-op effectively).
        val clip = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, w, h))
            if (center != null && progress > 0f) {
                val maxR = maxCornerDistance(center, w, h)
                val r = maxR * progress
                addOval(Rect(
                    left = center.x - r,
                    top = center.y - r,
                    right = center.x + r,
                    bottom = center.y + r,
                ))
            }
        }

        clipPath(clip) {
            // Bucket particles by per-particle alpha so we can emit them as 3 batched
            // `drawPoints` calls — much cheaper than one `drawCircle` per particle.
            // Each bucket gets a single uniform-alpha colour; the visual variation
            // across thresholds reads as "twinkling".
            val globalFade = 1f - progress
            val bucketA = mutableListOf<Offset>()
            val bucketB = mutableListOf<Offset>()
            val bucketC = mutableListOf<Offset>()
            for (p in particles) {
                val life = (1f - p.age / p.lifetime).coerceIn(0f, 1f)
                val a = life * globalFade
                if (a <= 0.05f) continue
                val off = Offset(p.x, p.y)
                when {
                    a > 0.66f -> bucketA.add(off)
                    a > 0.33f -> bucketB.add(off)
                    else      -> bucketC.add(off)
                }
            }

            val dotPx = 1.7f.dp.toPx()
            if (bucketA.isNotEmpty()) drawPoints(
                points = bucketA, pointMode = PointMode.Points,
                color = particleColor.copy(alpha = 0.85f),
                strokeWidth = dotPx, cap = StrokeCap.Round,
            )
            if (bucketB.isNotEmpty()) drawPoints(
                points = bucketB, pointMode = PointMode.Points,
                color = particleColor.copy(alpha = 0.55f),
                strokeWidth = dotPx, cap = StrokeCap.Round,
            )
            if (bucketC.isNotEmpty()) drawPoints(
                points = bucketC, pointMode = PointMode.Points,
                color = particleColor.copy(alpha = 0.25f),
                strokeWidth = dotPx, cap = StrokeCap.Round,
            )
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

private fun maxCornerDistance(center: Offset, w: Float, h: Float): Float {
    val d1 = hypot(center.x, center.y)
    val d2 = hypot(w - center.x, center.y)
    val d3 = hypot(center.x, h - center.y)
    val d4 = hypot(w - center.x, h - center.y)
    return max(max(d1, d2), max(d3, d4))
}
