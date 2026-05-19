/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.video

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.KeepScreenOn
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.local.PlayableState
import io.element.android.libraries.mediaviewer.impl.local.player.DEFAULT_PLAYBACK_SPEED
import io.element.android.libraries.mediaviewer.impl.local.player.AutoEnterPictureInPictureEffect
import io.element.android.libraries.mediaviewer.impl.local.player.FrameSnapshotter
import io.element.android.libraries.mediaviewer.impl.local.player.MediaPlayerControllerState
import io.element.android.libraries.mediaviewer.impl.local.player.MediaPlayerControllerView
import io.element.android.libraries.mediaviewer.impl.local.player.MiniPlayerCorner
import io.element.android.libraries.mediaviewer.impl.local.player.findActivity
import io.element.android.libraries.mediaviewer.impl.local.player.OrientationLockEffect
import io.element.android.libraries.mediaviewer.impl.local.player.VideoResizeMode
import io.element.android.libraries.mediaviewer.impl.local.player.rememberExoPlayer
import io.element.android.libraries.mediaviewer.impl.local.player.rememberPictureInPictureState
import io.element.android.libraries.mediaviewer.impl.local.player.seekToEnsurePlaying
import io.element.android.libraries.mediaviewer.impl.local.player.toExo
import io.element.android.libraries.mediaviewer.impl.local.player.togglePlay
import io.element.android.libraries.mediaviewer.impl.local.player.videoAspectRatio
import kotlinx.coroutines.launch
import io.element.android.libraries.mediaviewer.impl.local.rememberLocalMediaViewState
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.zoomable
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MediaVideoView(
    isDisplayed: Boolean,
    localMediaViewState: LocalMediaViewState,
    bottomPaddingInPixels: Int,
    localMedia: LocalMedia?,
    autoplay: Boolean,
    audioFocus: AudioFocus?,
    modifier: Modifier = Modifier,
) {
    val exoPlayer = rememberExoPlayer()
    ExoPlayerMediaVideoView(
        isDisplayed = isDisplayed,
        localMediaViewState = localMediaViewState,
        bottomPaddingInPixels = bottomPaddingInPixels,
        exoPlayer = exoPlayer,
        localMedia = localMedia,
        autoplay = autoplay,
        audioFocus = audioFocus,
        modifier = modifier,
    )
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun ExoPlayerMediaVideoView(
    isDisplayed: Boolean,
    localMediaViewState: LocalMediaViewState,
    bottomPaddingInPixels: Int,
    exoPlayer: ExoPlayer,
    localMedia: LocalMedia?,
    autoplay: Boolean,
    audioFocus: AudioFocus?,
    modifier: Modifier = Modifier,
) {
    val pipState = rememberPictureInPictureState()
    var mediaPlayerControllerState: MediaPlayerControllerState by remember {
        mutableStateOf(
            MediaPlayerControllerState(
                isVisible = true,
                isPlaying = false,
                isReady = false,
                progressInMillis = 0,
                durationInMillis = 0,
                canMute = true,
                isMuted = false,
                seekingToMillis = null,
                resizeMode = VideoResizeMode.ORIGINAL,
                playbackSpeed = DEFAULT_PLAYBACK_SPEED,
                isOrientationLocked = false,
                canCaptureFrame = localMedia?.uri != null,
                isCapturingFrame = false,
                canEnterPictureInPicture = pipState.available,
                isInPictureInPicture = false,
            )
        )
    }
    // Keep PiP capability flag in state in sync with the system / activity manifest
    // — recomposition-cheap derive rather than copy-and-assign in init, because
    // pipState.available can flip if the user grants/revokes permissions live.
    LaunchedEffect(pipState.available, pipState.isInPiP.value) {
        mediaPlayerControllerState = mediaPlayerControllerState.copy(
            canEnterPictureInPicture = pipState.available,
            isInPictureInPicture = pipState.isInPiP.value,
        )
    }
    // Snapshot button visibility tracks whether we have a URI to capture from
    // — image-only items pass null, and the controller bar hides the button.
    LaunchedEffect(localMedia?.uri) {
        mediaPlayerControllerState = mediaPlayerControllerState.copy(
            canCaptureFrame = localMedia?.uri != null,
        )
    }

    // Current video aspect ratio for system PiP auto-enter. Populated by the
    // player listener (added below) as soon as the first onVideoSizeChanged
    // fires; passed to AutoEnterPictureInPictureEffect so Android 12+ has
    // the right aspect ready when the user backgrounds the app.
    var videoAspectRatio by remember { mutableStateOf<android.util.Rational?>(null) }
    // Video player's on-screen rect — captured via onGloballyPositioned on
    // the PlayerView's AndroidView wrapper. Passed as `sourceRectHint` so
    // system PiP zooms to just the video area instead of the whole
    // activity (which would otherwise drag composer / headers into PiP).
    var videoBoundsInWindow by remember { mutableStateOf<android.graphics.Rect?>(null) }
    // 3-slot YouTube-style PiP action row: skip-back / play-pause /
    // skip-forward. Filling all three slots evicts the centred "[_]"
    // PiP menu hint that Android shows when the row has empty slots.
    // The seek deltas are clamped against currentPosition / duration so a
    // skip near the start / end snaps to the bound instead of seeking past.
    AutoEnterPictureInPictureEffect(
        aspectRatio = videoAspectRatio,
        sourceRectHint = videoBoundsInWindow,
        isPlaying = mediaPlayerControllerState.isPlaying,
        onPlayPause = { exoPlayer.togglePlay() },
        onSkipBack = {
            val pos = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
            exoPlayer.seekTo(pos)
            // Immediately reflect the new position in state so the PiP
            // red progress bar updates the same frame the action fires.
            // Otherwise the bar lags by up to one polling-loop tick
            // (200 ms) — and the loop itself can stall while the activity
            // is paused for PiP, so the bar appeared to not move at all
            // until the next playback frame.
            mediaPlayerControllerState = mediaPlayerControllerState.copy(progressInMillis = pos)
        },
        onSkipForward = {
            val pos = (exoPlayer.currentPosition + 10_000L)
                .coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
            exoPlayer.seekTo(pos)
            mediaPlayerControllerState = mediaPlayerControllerState.copy(progressInMillis = pos)
        },
    )

    val playableState: PlayableState.Playable by remember {
        derivedStateOf {
            PlayableState.Playable(
                isShowingControls = mediaPlayerControllerState.isVisible,
            )
        }
    }

    localMediaViewState.playableState = playableState

    val playerListener = remember {
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                localMediaViewState.isReady = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Timber.d("[mini-pip] onIsPlayingChanged: isPlaying=%s playWhenReady=%s state=%d",
                    isPlaying, exoPlayer.playWhenReady, exoPlayer.playbackState)
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isPlaying = isPlaying,
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Timber.d("[mini-pip] onPlayWhenReadyChanged: playWhenReady=%s reason=%d",
                    playWhenReady, reason)
            }

            override fun onVolumeChanged(volume: Float) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isMuted = volume == 0f,
                )
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                    exoPlayer.duration.takeIf { it >= 0 }
                        ?.let {
                            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                durationInMillis = it,
                            )
                        }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Timber.d("[mini-pip] onPlaybackStateChanged: state=%d (1=IDLE 2=BUFFERING 3=READY 4=ENDED) playWhenReady=%s",
                    playbackState, exoPlayer.playWhenReady)
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isReady = playbackState == STATE_READY,
                )
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = android.util.Rational(videoSize.width, videoSize.height)
                }
            }
        }
    }

    var autoHideController by remember { mutableIntStateOf(0) }

    LaunchedEffect(autoHideController) {
        delay(5.seconds)
        if (exoPlayer.isPlaying) {
            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                isVisible = false,
            )
        }
    }

    // Resize-mode toast: surface the newly-selected mode's label as a
    // brief centre overlay when the user cycles modes. `null` means
    // hidden. `firstResizeRender` skips the initial composition so the
    // toast doesn't pop on screen for no reason when the player loads.
    var resizeModeToast by remember { mutableStateOf<String?>(null) }
    var firstResizeRender by remember { mutableStateOf(true) }
    LaunchedEffect(mediaPlayerControllerState.resizeMode) {
        if (firstResizeRender) {
            firstResizeRender = false
            return@LaunchedEffect
        }
        resizeModeToast = mediaPlayerControllerState.resizeMode.label
        delay(1500)
        resizeModeToast = null
    }

    if (localMedia?.uri != null) {
        LaunchedEffect(localMedia.uri) {
            val mediaItem = MediaItem.fromUri(localMedia.uri)
            exoPlayer.setMediaItem(mediaItem)
        }
    } else {
        exoPlayer.setMediaItems(emptyList())
    }
    KeepScreenOn(mediaPlayerControllerState.isPlaying)

    // Push playback-speed changes to ExoPlayer the moment the user picks a
    // value. ExoPlayer's internal SoundTouch keeps audio pitch correct, so
    // 2x doesn't sound chipmunky — no extra wiring needed for that.
    LaunchedEffect(mediaPlayerControllerState.playbackSpeed) {
        exoPlayer.setPlaybackSpeed(mediaPlayerControllerState.playbackSpeed)
    }

    // Orientation lock effect — handles activity.requestedOrientation set/restore
    // including the dispose path when the user navigates away mid-lock.
    OrientationLockEffect(locked = mediaPlayerControllerState.isOrientationLocked)

    val coroutineScope = rememberCoroutineScope()
    val isInAppPiP = mediaPlayerControllerState.isInAppPiP

    // Two-phase snapshot feedback animation:
    //   1. Thumbnail preview pops in (centred-bottom of the video),
    //      sits visible for ~1 s, then slides down off-screen.
    //   2. After the thumbnail leaves, the "Galeriye kaydedildi"
    //      toast slides up from the bottom, sits for ~1.6 s, slides
    //      back out.
    // Re-keyed on `lastSnapshotUri` so a second snapshot during the
    // animation restarts the sequence cleanly.
    var thumbnailVisible by remember { mutableStateOf(false) }
    var savedToastVisible by remember { mutableStateOf(false) }
    LaunchedEffect(mediaPlayerControllerState.lastSnapshotUri) {
        if (mediaPlayerControllerState.lastSnapshotUri != null) {
            savedToastVisible = false   // reset if a second snap happens fast
            thumbnailVisible = true
            delay(1000)                 // thumbnail dwell
            thumbnailVisible = false    // triggers slide-down exit
            delay(450)                  // wait for exit animation to finish
            savedToastVisible = true
            delay(1600)                 // toast dwell
            savedToastVisible = false
            delay(400)                  // wait for toast exit
            mediaPlayerControllerState = mediaPlayerControllerState.copy(lastSnapshotUri = null)
        }
    }

    // System PiP wipes our Compose controller bar visibility — squishing
    // the bottom controls into a small PiP window looks like garbage. Hide
    // our own controls while the OS has us in PiP, restore them when the
    // user expands back. We can't hide the parent screen's composer /
    // header from here (those live above MediaVideoView), so the full
    // "clean video in PiP" UX needs a follow-up pass on the parent.
    LaunchedEffect(mediaPlayerControllerState.isInPictureInPicture) {
        mediaPlayerControllerState = if (mediaPlayerControllerState.isInPictureInPicture) {
            mediaPlayerControllerState.copy(isVisible = false)
        } else {
            mediaPlayerControllerState.copy(isVisible = true)
        }
    }

    // Safety net: after the user exits the in-app mini back to fullscreen,
    // ensure ExoPlayer is still trying to play. The transition can briefly
    // put the player into STATE_BUFFERING as the parent layout re-measures
    // (especially with the SurfaceView reattaching to a different-sized
    // parent), and on some devices the player never automatically resumes.
    LaunchedEffect(mediaPlayerControllerState.isInAppPiP) {
        Timber.d("[mini-pip] isInAppPiP changed -> %s | isPlaying=%s playWhenReady=%s state=%d",
            mediaPlayerControllerState.isInAppPiP, exoPlayer.isPlaying,
            exoPlayer.playWhenReady, exoPlayer.playbackState)
        if (!mediaPlayerControllerState.isInAppPiP && exoPlayer.playWhenReady) {
            delay(100)
            Timber.d("[mini-pip] safety-net check after 100ms: isPlaying=%s playWhenReady=%s state=%d",
                exoPlayer.isPlaying, exoPlayer.playWhenReady, exoPlayer.playbackState)
            if (!exoPlayer.isPlaying && exoPlayer.playWhenReady) {
                Timber.d("[mini-pip] safety-net calling play()")
                exoPlayer.play()
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(ElementTheme.colors.bgSubtlePrimary),
    ) {
        val context = LocalContext.current
        // Parent dimensions captured here drive the snap-to-corner logic
        // when the user drops a dragged mini player; without knowing the
        // outer Box's bounds we'd have to guess at which corner is closest.
        val density = LocalDensity.current
        val parentWidthPx = with(density) { maxWidth.toPx() }
        val parentHeightPx = with(density) { maxHeight.toPx() }
        // Base (scale = 1.0) mini size — 0.6 of parent width × 16:9. Users
        // can pinch up to 1.5 × (almost full width) or down to 0.5 × (small
        // floating thumbnail), same range YouTube's mini player allows.
        val baseMiniWidthPx = parentWidthPx * 0.6f
        val baseMiniHeightPx = baseMiniWidthPx * 9f / 16f

        if (LocalInspectionMode.current) {
            Text(
                modifier = Modifier
                    .background(ElementTheme.colors.bgSubtlePrimary)
                    .align(Alignment.Center),
                text = "A Video Player will render here",
            )
        } else {
            // Free positioning + pinch-to-zoom for the mini player.
            //
            // `miniScale` ranges from 0.5 (compact thumbnail) to 1.5 (almost
            // full width). `miniPosPx` is the absolute top-left of the
            // floating window in parent-pixel coords; null means "use the
            // default corner-derived position" so the first show lands
            // somewhere sensible.
            //
            // Drag perf: miniPosPx is read ONLY inside the .offset lambda
            // (not in the composable body), so a pan delta invalidates just
            // the offset-modifier layer instead of the whole MediaVideoView.
            // Reading it up here would cause the BoxWithConstraints body to
            // recompose on every finger move — that was the jitter source.
            var miniScale by remember { mutableStateOf(1f) }
            var miniPosPx by remember { mutableStateOf<Offset?>(null) }

            // Mini-mode local state belongs to mini mode only — reset it
            // whenever we drop back to fullscreen. Without this reset the
            // *next* time the user entered mini, the previous drag position
            // would be remembered (sometimes wanted, sometimes confusing),
            // and the stale offset / scale could also bleed visually into
            // the fullscreen view during the transition frame.
            LaunchedEffect(isInAppPiP) {
                if (!isInAppPiP) {
                    miniPosPx = null
                    miniScale = 1f
                }
            }

            val currentMiniWidthPx = baseMiniWidthPx * miniScale
            val currentMiniHeightPx = baseMiniHeightPx * miniScale
            val edgePadPx = with(density) { 12.dp.toPx() }
            val cornerForDefault = mediaPlayerControllerState.miniPlayerCorner
            val miniWidthDp = with(density) { currentMiniWidthPx.toDp() }
            val miniHeightDp = with(density) { currentMiniHeightPx.toDp() }

            val playerWrapperModifier = if (isInAppPiP) {
                Modifier
                    .offset {
                        // Read miniPosPx HERE (not in composable body) so
                        // pan-driven position updates re-run only this
                        // lambda — the parent doesn't recompose, the
                        // wrapper modifier chain doesn't rebuild, no jitter.
                        val pos = miniPosPx ?: when (cornerForDefault) {
                            MiniPlayerCorner.TOP_START -> Offset(edgePadPx, edgePadPx)
                            MiniPlayerCorner.TOP_END ->
                                Offset(parentWidthPx - currentMiniWidthPx - edgePadPx, edgePadPx)
                            MiniPlayerCorner.BOTTOM_START ->
                                Offset(edgePadPx, parentHeightPx - currentMiniHeightPx - edgePadPx)
                            MiniPlayerCorner.BOTTOM_END ->
                                Offset(
                                    parentWidthPx - currentMiniWidthPx - edgePadPx,
                                    parentHeightPx - currentMiniHeightPx - edgePadPx,
                                )
                        }
                        IntOffset(pos.x.toInt(), pos.y.toInt())
                    }
                    .size(width = miniWidthDp, height = miniHeightDp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        // Combined pan + zoom: detectTransformGestures fires
                        // on any pointer movement with both delta-pan and
                        // delta-zoom in one callback. Single-finger drag has
                        // zoom = 1.0, so position updates normally; two-
                        // finger pinch scales the window. Both clamped so
                        // the mini can't pinch past sensible bounds or drag
                        // off-screen (always at least partially visible).
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (miniScale * zoom).coerceIn(0.5f, 1.5f)
                            val newWidthPx = baseMiniWidthPx * newScale
                            val newHeightPx = baseMiniHeightPx * newScale
                            val currentPos = miniPosPx ?: when (cornerForDefault) {
                                MiniPlayerCorner.TOP_START -> Offset(edgePadPx, edgePadPx)
                                MiniPlayerCorner.TOP_END ->
                                    Offset(parentWidthPx - currentMiniWidthPx - edgePadPx, edgePadPx)
                                MiniPlayerCorner.BOTTOM_START ->
                                    Offset(edgePadPx, parentHeightPx - currentMiniHeightPx - edgePadPx)
                                MiniPlayerCorner.BOTTOM_END ->
                                    Offset(
                                        parentWidthPx - currentMiniWidthPx - edgePadPx,
                                        parentHeightPx - currentMiniHeightPx - edgePadPx,
                                    )
                            }
                            val nextX = (currentPos.x + pan.x).coerceIn(
                                0f, (parentWidthPx - newWidthPx).coerceAtLeast(0f),
                            )
                            val nextY = (currentPos.y + pan.y).coerceIn(
                                0f, (parentHeightPx - newHeightPx).coerceAtLeast(0f),
                            )
                            miniScale = newScale
                            miniPosPx = Offset(nextX, nextY)
                        }
                    }
                    .pointerInput(Unit) {
                        // Double-tap = YouTube-style shortcut to expand back
                        // to fullscreen. Single-tap is no longer a control-
                        // toggle (controls are always visible now), so this
                        // detector only listens for the double.
                        detectTapGestures(
                            onDoubleTap = {
                                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                    isInAppPiP = false,
                                    isVisible = true,
                                )
                            },
                        )
                    }
            } else {
                Modifier.fillMaxSize()
            }
            Box(modifier = playerWrapperModifier) {
                // Single AndroidView call site — modifier changes between
                // modes but the View instance is preserved by Compose's
                // normal reconciliation. The previous attempt to use
                // `movableContentOf` + `key(isInAppPiP)` caused the video
                // surface to glitch / shift on PiP transitions because the
                // wrapper Box was being torn down and rebuilt around it.
                // The pause-on-transition bug we were trying to fix with
                // those is already handled by the ExoPlayerLifecycleHelper
                // change to ON_STOP-only pausing.
                val androidViewModifier = if (isInAppPiP) {
                    Modifier.fillMaxSize()
                } else {
                    val aspect = mediaPlayerControllerState.resizeMode.aspectRatio
                    val baseModifier = if (aspect != null) {
                        // Centred vertically inside the parent fillMaxSize
                        // Box. Without this the aspect-constrained
                        // container glued to the top of the screen and
                        // left a big black gap below, which the user
                        // spotted in 16:9 / 4:3 modes.
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    baseModifier.zoomable(
                        state = localMediaViewState.zoomableState,
                        onClick = {
                            autoHideController++
                            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                isVisible = !mediaPlayerControllerState.isVisible,
                            )
                        }
                    )
                }
                    .onGloballyPositioned { coords ->
                        // Update the system PiP source-rect hint on every
                        // layout pass so a rotation / split-screen change
                        // keeps the rect accurate. boundsInWindow gives the
                        // PlayerView's absolute window coords, which is
                        // exactly what setSourceRectHint expects.
                        val r = coords.boundsInWindow()
                        videoBoundsInWindow = android.graphics.Rect(
                            r.left.toInt(),
                            r.top.toInt(),
                            r.right.toInt(),
                            r.bottom.toInt(),
                        )
                    }
                AndroidView(
                    modifier = androidViewModifier,
                    factory = {
                        PlayerView(context).apply {
                            player = exoPlayer
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            useController = false
                        }
                    },
                    update = { playerView ->
                        playerView.resizeMode = if (isInAppPiP) {
                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        } else {
                            mediaPlayerControllerState.resizeMode.toExo()
                        }
                        playerView.useController = false
                    },
                    onRelease = { playerView ->
                        playerView.player = null
                    },
                )
                // Thin red progress bar at the bottom of the video — only
                // when in system PiP. Activity layout is captured into the
                // PiP window, so anything painted inside this Box shows
                // up there. Lets the user see playback position at a
                // glance inside the small PiP window, just like YouTube.
                // Skipped outside PiP (in-app mini has its own seekable
                // bar; the fullscreen MediaPlayerControllerView slider is
                // the seek affordance there — adding a duplicate would
                // just sit below the slider as visual noise, which is
                // exactly the bug the user spotted).
                if (mediaPlayerControllerState.isInPictureInPicture) {
                    val fsDuration = mediaPlayerControllerState.durationInMillis
                    val fsProgress = if (fsDuration > 0L) {
                        (mediaPlayerControllerState.displayProgressInMillis.toFloat() / fsDuration.toFloat())
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(fsProgress)
                            .height(2.dp)
                            .background(Color.Red),
                    )
                }
                if (isInAppPiP) {
                    // Mini overlay controls — in-app mini only. System PiP
                    // used to render these too for visual consistency, but
                    // the user found it confusing (the buttons look
                    // tappable but Android intercepts touches in PiP
                    // and turns every tap into "expand back to app"). For
                    // system PiP we rely on the bottom-row RemoteActions
                    // instead — same compound icons, actually functional.
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Centered play/pause — bigger tap target than the
                        // 36 dp main controller version so it stays usable
                        // at mini size.
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                .clip(CircleShape)
                                .clickable { exoPlayer.togglePlay() }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (mediaPlayerControllerState.isPlaying) {
                                    CompoundIcons.PauseSolid()
                                } else {
                                    CompoundIcons.PlaySolid()
                                },
                                tint = Color.White,
                                contentDescription = if (mediaPlayerControllerState.isPlaying) "Pause" else "Play",
                            )
                        }
                        // Top-right: dismiss the mini.
                        IconButton(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(30.dp),
                            onClick = {
                                Timber.d("[mini-pip] Close button clicked | isPlaying=%s playWhenReady=%s",
                                    exoPlayer.isPlaying, exoPlayer.playWhenReady)
                                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                    isInAppPiP = false,
                                    isVisible = true,
                                )
                            },
                        ) {
                            Icon(
                                imageVector = CompoundIcons.Close(),
                                tint = Color.White,
                                contentDescription = "Close",
                            )
                        }
                        // Top-left: expand back to fullscreen. Same action
                        // as Close in our context (the host screen IS the
                        // fullscreen player) but kept separate for the
                        // affordance — the icon explicitly says "expand".
                        IconButton(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .size(30.dp),
                            onClick = {
                                Timber.d("[mini-pip] Expand button clicked | isPlaying=%s playWhenReady=%s",
                                    exoPlayer.isPlaying, exoPlayer.playWhenReady)
                                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                    isInAppPiP = false,
                                    isVisible = true,
                                )
                            },
                        ) {
                            Icon(
                                imageVector = CompoundIcons.Expand(),
                                tint = Color.White,
                                contentDescription = "Expand to fullscreen",
                            )
                        }
                    }
                    // YouTube-style thin red progress bar with a draggable
                    // thumb. Outer Box is 16 dp tall (comfortable touch
                    // target), painted bar 2 dp (YouTube thickness), and a
                    // 12 dp white circle thumb sits centred on the bar at
                    // the current progress position.
                    //
                    // Reading `displayProgressInMillis` (= seekingToMillis
                    // ?: progressInMillis) instead of progressInMillis so
                    // the bar + thumb visually track the user's finger
                    // mid-scrub. With raw progressInMillis the polling
                    // loop only updates on playback ticks, so during a
                    // drag the thumb appeared frozen at the start point.
                    val duration = mediaPlayerControllerState.durationInMillis
                    val progress = if (duration > 0L) {
                        (mediaPlayerControllerState.displayProgressInMillis.toFloat() / duration.toFloat())
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(16.dp)
                            .pointerInput(duration) {
                                if (duration <= 0L) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    // Pause while scrubbing — the user is
                                    // hunting for a frame, not watching.
                                    // The previous code called
                                    // seekToEnsurePlaying which forced
                                    // play() on every position change,
                                    // making the video stutter forward
                                    // mid-drag. Capture the prior intent
                                    // and restore it on release.
                                    val wasPlaying = exoPlayer.playWhenReady
                                    if (wasPlaying) exoPlayer.pause()
                                    val width = size.width.toFloat()
                                    val initial = (down.position.x / width).coerceIn(0f, 1f)
                                    val initialMs = (initial * duration).toLong()
                                    exoPlayer.seekTo(initialMs)
                                    mediaPlayerControllerState =
                                        mediaPlayerControllerState.copy(seekingToMillis = initialMs)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val frac = (change.position.x / width).coerceIn(0f, 1f)
                                        val ms = (frac * duration).toLong()
                                        exoPlayer.seekTo(ms)
                                        mediaPlayerControllerState =
                                            mediaPlayerControllerState.copy(seekingToMillis = ms)
                                    }
                                    if (wasPlaying) exoPlayer.play()
                                }
                            },
                    ) {
                        val barWidthPx = constraints.maxWidth.toFloat()
                        val thumbSize = 12.dp
                        val thumbSizePx = with(LocalDensity.current) { thumbSize.toPx() }
                        // Dim background track (full width).
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.Red.copy(alpha = 0.35f)),
                        )
                        // Filled portion up to current progress.
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth(progress)
                                .height(2.dp)
                                .background(Color.Red),
                        )
                        // Thumb circle at the progress point. Vertically
                        // centred so the thumb sits ON the bar; the
                        // CenterStart alignment + x-offset places it
                        // horizontally at `progress * width`.
                        val thumbXPx = (barWidthPx * progress - thumbSizePx / 2f)
                            .coerceIn(0f, barWidthPx - thumbSizePx)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset { IntOffset(thumbXPx.toInt(), 0) }
                                .size(thumbSize)
                                .background(Color.White, CircleShape),
                        )
                    }
                }
            }
        }
        // Top-end snapshot capture button — reachable even when the
        // bottom controller bar is hidden. Mirrors a stock camera-shutter
        // affordance: filled photo icon, becomes a small progress
        // indicator while the frame is being grabbed + written to disk.
        // Hidden inside in-app mini and system PiP (no use for it
        // there).
        if (mediaPlayerControllerState.canCaptureFrame &&
            !mediaPlayerControllerState.isInAppPiP &&
            !mediaPlayerControllerState.isInPictureInPicture) {
            IconButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                onClick = {
                    autoHideController++
                    val uri = localMedia?.uri ?: return@IconButton
                    if (mediaPlayerControllerState.isCapturingFrame) return@IconButton
                    val captureAt = exoPlayer.currentPosition
                    val targetAspect = mediaPlayerControllerState.resizeMode.aspectRatio
                    mediaPlayerControllerState =
                        mediaPlayerControllerState.copy(isCapturingFrame = true)
                    coroutineScope.launch {
                        val out = FrameSnapshotter.capture(context, uri, captureAt, targetAspect)
                        Timber.d("FrameSnapshotter result: %s", out)
                        mediaPlayerControllerState = mediaPlayerControllerState.copy(
                            isCapturingFrame = false,
                            lastSnapshotUri = out,
                        )
                    }
                },
                enabled = !mediaPlayerControllerState.isCapturingFrame,
            ) {
                if (mediaPlayerControllerState.isCapturingFrame) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = CompoundIcons.TakePhotoSolid(),
                        tint = Color.White,
                        contentDescription = "Take snapshot",
                    )
                }
            }
        }
        // Resize-mode toast — centred over the video, fades in/out for
        // ~1.5 s after the user cycles the resize button so they know
        // which mode they just switched to (e.g. "3:4", "Orijinal").
        AnimatedVisibility(
            visible = resizeModeToast != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val label = resizeModeToast
            if (label != null) {
                Box(
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(text = label, color = Color.White)
                }
            }
        }
        // Phase 1 — snapshot thumbnail preview. Pops in centred-bottom
        // when a snapshot lands, sits visible briefly, then slides down
        // off-screen via the exit animation. Backed by the in-flight
        // `lastSnapshotUri` so it renders the actual just-captured frame.
        AnimatedVisibility(
            visible = thumbnailVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (bottomPaddingInPixels.toDp() + 96.dp)),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it * 2 },
        ) {
            val snapUri = mediaPlayerControllerState.lastSnapshotUri
            if (snapUri != null) {
                // Use the saved snapshot's actual aspect ratio for the
                // preview so the thumbnail looks like a miniature of the
                // real image (not a forced square that crops content).
                // resize-mode aspect when one is set; otherwise the
                // native video aspect ExoPlayer last reported.
                val previewAspect = mediaPlayerControllerState.resizeMode.aspectRatio
                    ?: videoAspectRatio?.let { it.numerator.toFloat() / it.denominator }
                    ?: (16f / 9f)
                AsyncImage(
                    model = snapUri,
                    contentDescription = "Snapshot preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(80.dp)
                        .aspectRatio(previewAspect)
                        .shadow(6.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                )
            }
        }
        // Phase 2 — "Galeriye kaydedildi" toast. Slides up from the
        // bottom after the thumbnail has finished its exit animation
        // (sequencing handled by the LaunchedEffect above), sits for
        // ~1.6 s, slides back out.
        AnimatedVisibility(
            visible = savedToastVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (bottomPaddingInPixels.toDp() + 96.dp)),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Galeriye kaydedildi",
                    color = Color.White,
                )
            }
        }
        // MediaPlayerControllerView stays mounted even when the in-app mini
        // player is active — it just hides itself visually via its own
        // AnimatedVisibility on state.isVisible (we set isVisible=false when
        // entering mini). Unmounting it during mini mode used to cancel its
        // audio-focus LaunchedEffect; re-mounting on the way back triggered
        // a focus re-request, the OS reported a brief AUDIO_FOCUS_LOSS, and
        // ExoPlayer paused with PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        // — that was the "video pauses on return from mini" bug.
        MediaPlayerControllerView(
            state = mediaPlayerControllerState,
                onTogglePlay = {
                    autoHideController++
                    exoPlayer.togglePlay()
                },
                onSeekChange = {
                    autoHideController++
                    mediaPlayerControllerState = mediaPlayerControllerState.copy(
                        seekingToMillis = it.toLong(),
                    )
                    exoPlayer.seekToEnsurePlaying(it.toLong())
                },
                onToggleMute = {
                    autoHideController++
                    exoPlayer.volume = if (exoPlayer.volume == 1f) 0f else 1f
                },
                onToggleResizeMode = {
                    autoHideController++
                    mediaPlayerControllerState = mediaPlayerControllerState.copy(
                        resizeMode = mediaPlayerControllerState.resizeMode.next(),
                    )
                },
                onSelectPlaybackSpeed = { speed ->
                    autoHideController++
                    mediaPlayerControllerState = mediaPlayerControllerState.copy(playbackSpeed = speed)
                },
                onToggleOrientationLock = {
                    autoHideController++
                    mediaPlayerControllerState = mediaPlayerControllerState.copy(
                        isOrientationLocked = !mediaPlayerControllerState.isOrientationLocked,
                    )
                },
                onCaptureFrame = {
                    autoHideController++
                    val uri = localMedia?.uri ?: return@MediaPlayerControllerView
                    if (mediaPlayerControllerState.isCapturingFrame) return@MediaPlayerControllerView
                    val captureAt = exoPlayer.currentPosition
                    mediaPlayerControllerState = mediaPlayerControllerState.copy(isCapturingFrame = true)
                    coroutineScope.launch {
                        val out = FrameSnapshotter.capture(context, uri, captureAt)
                        Timber.d("FrameSnapshotter result: %s", out)
                        mediaPlayerControllerState = mediaPlayerControllerState.copy(
                            isCapturingFrame = false,
                            // Surface the saved URI so the top-left preview
                            // overlay fades in. The 2 s clear is wired in a
                            // LaunchedEffect at the top of MediaVideoView.
                            lastSnapshotUri = out,
                        )
                    }
                },
                onRotate = {
                    autoHideController++
                    val activity = context.findActivity() ?: return@MediaPlayerControllerView
                    // 4-state 90° clockwise cycle:
                    //   PORTRAIT → LANDSCAPE → REVERSE_PORTRAIT → REVERSE_LANDSCAPE
                    // Using explicit (non-SENSOR) orientations means the
                    // rotation sticks regardless of the lock state — the
                    // user explicitly asked for the Rotate button to work
                    // even while orientation lock is on, so this overrides
                    // whatever the lock effect set previously and the lock
                    // effect (keyed only on `locked`, not on config) won't
                    // fight back.
                    val current = activity.requestedOrientation
                    activity.requestedOrientation = when (current) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ->
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ->
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else -> {
                            // Sensor / UNSPECIFIED / LOCKED — pick the
                            // 90°-from-current explicit orientation as
                            // the entry into the cycle.
                            val configLandscape =
                                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (configLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                        }
                    }
                    // Reflect the rotation as a lock in the UI: the
                    // user just fixed the orientation explicitly, so
                    // the lock icon should show that state. They tap
                    // Lock again to release back to sensor mode (which
                    // the OrientationLockEffect.onDispose handles by
                    // restoring SCREEN_ORIENTATION_UNSPECIFIED).
                    mediaPlayerControllerState =
                        mediaPlayerControllerState.copy(isOrientationLocked = true)
                },
                onEnterPictureInPicture = {
                    autoHideController++
                    // In-app PiP: shrink the player to a bottom-right floating
                    // window inside the current screen. System PiP still kicks
                    // in automatically when the user actually backgrounds the
                    // app — that's wired by enabling auto-enter in the params
                    // (PictureInPictureHelper.enterPiP). We deliberately do not
                    // call pipState.onEnter here — tapping the button while
                    // the app is foreground shouldn't yank the user out of the
                    // app, which is what the OS-level enter does.
                    mediaPlayerControllerState = mediaPlayerControllerState.copy(
                        isInAppPiP = true,
                        // Mini mode has no controller bar, so collapse the
                        // bottom controls' visibility flag too — otherwise it
                        // would briefly flash on the next isVisible toggle.
                        isVisible = false,
                    )
                },
                audioFocus = audioFocus,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPaddingInPixels.toDp()),
            )
    }

    LaunchedEffect(exoPlayer.isPlaying) {
        if (exoPlayer.isPlaying) {
            while (true) {
                val position = exoPlayer.currentPosition
                val seekingTo = mediaPlayerControllerState.seekingToMillis
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    progressInMillis = position,
                    seekingToMillis = if (seekingTo != null && position >= seekingTo) null else seekingTo,
                )
                delay(200)
            }
        } else {
            // Ensure we render the final state
            val position = exoPlayer.currentPosition
            val seekingTo = mediaPlayerControllerState.seekingToMillis
            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                progressInMillis = position,
                seekingToMillis = if (seekingTo != null && position >= seekingTo) null else seekingTo,
            )
        }
    }

    ExoPlayerLifecycleHelper(
        exoPlayer = exoPlayer,
        autoplay = autoplay,
        isDisplayed = isDisplayed,
        playerListener = playerListener,
        mediaPlayerControllerState = mediaPlayerControllerState,
    )
}

/**
 * Top-left pixel coordinates of a mini-player anchored at the given corner,
 * inside a parent of the given pixel dimensions. Used by the drag handler to
 * compute the mini's absolute position after a drop and pick the nearest
 * destination corner.
 */
private fun cornerBaseTopLeft(
    corner: MiniPlayerCorner,
    parentW: Float,
    parentH: Float,
    miniW: Float,
    miniH: Float,
): Offset = when (corner) {
    MiniPlayerCorner.TOP_START -> Offset(0f, 0f)
    MiniPlayerCorner.TOP_END -> Offset(parentW - miniW, 0f)
    MiniPlayerCorner.BOTTOM_START -> Offset(0f, parentH - miniH)
    MiniPlayerCorner.BOTTOM_END -> Offset(parentW - miniW, parentH - miniH)
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerLifecycleHelper(
    exoPlayer: ExoPlayer,
    autoplay: Boolean,
    isDisplayed: Boolean,
    playerListener: Player.Listener,
    mediaPlayerControllerState: MediaPlayerControllerState,
) {
    // Prepare and release the exoPlayer with the composable lifecycle
    DisposableEffect(Unit) {
        Timber.d("ExoPlayerMediaVideoView DisposableEffect: initializing exoPlayer")
        exoPlayer.addListener(playerListener)
        exoPlayer.prepare()

        onDispose {
            Timber.d("Disposing exoplayer")
            if (!exoPlayer.isReleased) {
                exoPlayer.removeListener(playerListener)
                exoPlayer.release()
            }
        }
    }

    var needsAutoPlay by remember { mutableStateOf(autoplay) }
    LaunchedEffect(needsAutoPlay, isDisplayed, mediaPlayerControllerState.isReady) {
        val isReadyAndNotPlaying = mediaPlayerControllerState.isReady && !mediaPlayerControllerState.isPlaying
        if (needsAutoPlay && isDisplayed && isReadyAndNotPlaying) {
            // When displayed, start autoplaying
            exoPlayer.play()
            needsAutoPlay = false
        } else if (!isDisplayed && mediaPlayerControllerState.isPlaying) {
            // If not displayed, make sure to pause the video
            exoPlayer.pause()
        }
    }

    // Pause playback only on ON_STOP (full background), NOT on ON_PAUSE.
    // ON_PAUSE fires both for "actual background" and for "transitioning to
    // PiP", and the isInPictureInPictureMode flag isn't reliably true yet at
    // that moment (race with onPictureInPictureModeChanged). ON_STOP only
    // fires on real backgrounding — PiP stays in STARTED state — so this
    // path correctly pauses when the user actually leaves the app while
    // leaving the video alone when the system shrinks us into PiP.
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_STOP && exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }
}

@PreviewsDayNight
@Composable
internal fun MediaVideoViewPreview() = ElementPreview {
    MediaVideoView(
        isDisplayed = true,
        modifier = Modifier.fillMaxSize(),
        bottomPaddingInPixels = 0,
        localMediaViewState = rememberLocalMediaViewState(),
        localMedia = null,
        audioFocus = null,
        autoplay = false,
    )
}
