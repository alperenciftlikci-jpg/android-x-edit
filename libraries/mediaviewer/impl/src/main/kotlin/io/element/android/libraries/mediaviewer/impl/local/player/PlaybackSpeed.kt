/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

/**
 * The set of playback rates the speed picker exposes. We deliberately keep the
 * list short — every option must read clearly as a label ("0.5×", "1×", ...) and
 * each step needs to feel meaningfully different. Anything finer than 0.25
 * increments here would just be a "lots of dots" menu without a perceptual gain.
 *
 * Audio pitch is preserved by ExoPlayer's built-in SoundTouch (default), so
 * 2× playback isn't chipmunk-voiced — the engine time-stretches the audio.
 */
val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

const val DEFAULT_PLAYBACK_SPEED: Float = 1f

/**
 * Display label for a playback rate. We strip a trailing `.0` so `1f` reads as
 * "1×" rather than "1.0×" — purely cosmetic, but the speed picker buttons are
 * narrow and the extra `.0` looks like noise.
 */
fun Float.formatPlaybackSpeed(): String {
    val rounded = if (this % 1f == 0f) this.toInt().toString() else this.toString()
    return "${rounded}×"
}
