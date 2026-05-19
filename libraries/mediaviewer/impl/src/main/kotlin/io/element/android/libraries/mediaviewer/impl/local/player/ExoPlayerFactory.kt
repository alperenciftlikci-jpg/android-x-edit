/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@AndroidXOptIn(UnstableApi::class)
@Composable
fun rememberExoPlayer(): ExoPlayer {
    return if (LocalInspectionMode.current) {
        remember {
            ExoPlayerForPreview()
        }
    } else {
        val context = LocalContext.current
        remember {
            ExoPlayer.Builder(context)
                // USAGE_MEDIA + CONTENT_TYPE_MOVIE tells the OS this is
                // foreground video playback, so it routes audio correctly
                // (Bluetooth A2DP, headphones, speaker), and handleAudioFocus
                // hands the focus negotiation back to ExoPlayer itself —
                // calls, alarms, other apps duck / pause us cleanly without
                // us writing manual focus glue.
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // Auto-pause when headphones / BT audio disconnect — without
                // this the video keeps playing and the audio re-routes to
                // the phone speaker the moment the user pulls earbuds out.
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
    }
}
