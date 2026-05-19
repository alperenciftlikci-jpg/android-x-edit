/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Resize / aspect-ratio modes the user can cycle through with the resize
 * button. `aspectRatio` is the target frame shape (width/height) the
 * player container is constrained to — null means "no override, let the
 * video pick its own ratio". `label` is what surfaces in the on-screen
 * toast when the user switches modes.
 *
 * FIT_SCREEN stretches the video to fill the entire screen with no
 * aspect preservation (RESIZE_MODE_FILL). All the fixed-ratio modes
 * (3:4 / 4:3 / 9:16 / 16:9) crop with RESIZE_MODE_ZOOM so the video
 * fills the constrained box without letterbox bars.
 */
enum class VideoResizeMode(val label: String, val aspectRatio: Float?) {
    ORIGINAL("Orijinal", null),
    ASPECT_3_4("3:4", 3f / 4f),
    ASPECT_4_3("4:3", 4f / 3f),
    ASPECT_9_16("9:16", 9f / 16f),
    ASPECT_16_9("16:9", 16f / 9f),
    FIT_SCREEN("Ekrana sığdır", null);

    /** Move to the next mode in the cycle. */
    fun next(): VideoResizeMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
internal fun VideoResizeMode.toExo(): Int = when (this) {
    // Native ratio with letterbox — preserves the video's own aspect.
    VideoResizeMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    // Forced container ratio: ZOOM crops the video to fill that container
    // shape (no letterbox), which is what the user wants when they
    // explicitly pick a numeric ratio.
    VideoResizeMode.ASPECT_3_4,
    VideoResizeMode.ASPECT_4_3,
    VideoResizeMode.ASPECT_9_16,
    VideoResizeMode.ASPECT_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    // Stretch the surface to fill — distorts aspect but maximises screen use.
    VideoResizeMode.FIT_SCREEN -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}
