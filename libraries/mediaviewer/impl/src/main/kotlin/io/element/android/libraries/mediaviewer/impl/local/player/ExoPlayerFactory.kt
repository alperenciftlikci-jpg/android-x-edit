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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

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
                // Mobile-tuned LoadControl. DefaultLoadControl defaults to
                // 50 000 ms maximum buffer which is huge for short attachment
                // videos on cellular data — the OS happily downloads the
                // entire file before the user even hits play. Tightening
                // to 15 s buffer / 30 s max / 1.5 s startup / 2 s rebuffer
                // mirrors what most modern video apps ship and keeps the
                // memory + network footprint reasonable.
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            /* minBufferMs = */ 15_000,
                            /* maxBufferMs = */ 30_000,
                            /* bufferForPlaybackMs = */ 1_500,
                            /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                        )
                        .build()
                )
                // Snappier seeks — PREVIOUS_SYNC jumps to the previous
                // keyframe instead of decoding to the exact requested
                // frame. Same trade-off the snapshot path took: speed >
                // ms-perfect accuracy for an interactive scrub bar. The
                // visible jump is usually under a second on H.264/HEVC.
                .setSeekParameters(SeekParameters.PREVIOUS_SYNC)
                // NOTE: do NOT lower setReleaseTimeoutMs below the 500 ms
                // default. The same timeout caps setForegroundMode(), which
                // ExoPlayer calls internally during state transitions; if the
                // device's codec driver doesn't flip foreground mode within
                // the window, the player raises ExoTimeoutException and
                // transitions to STATE_IDLE — which manifests as "video plays
                // for ~1 s then stops on its own". Worth flagging here so the
                // optimisation doesn't get re-tried.
                //
                // AudioAttributes are set so the OS routes audio correctly
                // (Bluetooth A2DP, headphones, speaker) for MOVIE content.
                // handleAudioFocus is INTENTIONALLY false: the app already
                // negotiates focus via [AudioFocus] in MediaPlayerControllerView.
                // Setting both to true causes a double-request race —
                // ExoPlayer's internal handler reads our app's request as a
                // focus loss and pauses ~1 s into playback. Hard-learned;
                // do not flip back to true without removing the app-level
                // AudioFocus loop first.
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ false,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    // Skip text/subtitle and metadata track selection — we
                    // don't render subtitles or ID3/timed-metadata in this
                    // viewer, so both renderers can stay idle. Disabling
                    // each frees its parse pipeline; per-video savings are
                    // small individually but cumulative across the renderer
                    // count, especially on streams with rich timed-text or
                    // EMSG metadata cues.
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                        .build()
                }
        }
    }
}
