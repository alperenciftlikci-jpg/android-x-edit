/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

@Composable
fun rememberExoPlayer(): ExoPlayer {
    return if (LocalInspectionMode.current) {
        remember {
            ExoPlayerForPreview()
        }
    } else {
        val context = LocalContext.current
        remember {
            // NextRenderersFactory adds FFmpeg-based software video decoders on top of
            // DefaultRenderersFactory. EXTENSION_RENDERER_MODE_ON means hardware (MediaCodec)
            // is tried first; FFmpeg only kicks in when the hardware decoder rejects the
            // profile (e.g. H.264 Hi10P, HEVC 4:2:2 10-bit) — so normal 8-bit playback
            // keeps its zero-cost hardware path.
            val renderersFactory = NextRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
            ExoPlayer.Builder(context, renderersFactory).build()
        }
    }
}
