/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui.components

import android.net.Uri
import androidx.annotation.OptIn as MediaOptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Telegram-style preview player for the video editor. ExoPlayer wrapped in `PlayerView`
 * (Media3) and mounted via `AndroidView`. The player auto-loops between `[trimStartMs,
 * trimEndMs]` so the user can preview the cut without manually rewinding.
 *
 * Lifecycle: ExoPlayer instance is recreated when the source URI changes (typical when
 * the editor is opened with a new video) and released on Dispose. Mute toggling is
 * handed through `volume` so we don't tear down the audio renderer mid-loop.
 */
@MediaOptIn(UnstableApi::class)
@Composable
fun TelegramVideoPlayer(
    sourceUri: Uri,
    isPlaying: Boolean,
    trimStartMs: Long,
    trimEndMs: Long,
    isMuted: Boolean,
    onCurrentPositionUpdate: (Long) -> Unit,
    modifier: Modifier = Modifier,
    seekToMs: Long? = null,
) {
    val context = LocalContext.current

    val player = remember(sourceUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            prepare()
        }
    }

    // Apply transient state (play/pause, mute) without rebuilding the player.
    LaunchedEffect(isPlaying) { player.playWhenReady = isPlaying }
    LaunchedEffect(isMuted)   { player.volume = if (isMuted) 0f else 1f }

    // External seek command (scrub via the trim timeline). LaunchedEffect re-fires on each
    // distinct ms value so rapid drags translate to a sequence of seekTos.
    LaunchedEffect(seekToMs) { seekToMs?.let { player.seekTo(it) } }

    // Loop within [trimStartMs, trimEndMs]: poll ~ 30 ms and seek when we cross the end.
    LaunchedEffect(trimStartMs, trimEndMs, sourceUri) {
        if (trimEndMs <= trimStartMs) return@LaunchedEffect
        // Initial position = trim start.
        player.seekTo(trimStartMs)
        while (true) {
            kotlinx.coroutines.delay(30)
            val pos = player.currentPosition
            onCurrentPositionUpdate(pos)
            if (pos >= trimEndMs) {
                player.seekTo(trimStartMs)
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false             // we provide our own play/pause UI
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
    )
}
