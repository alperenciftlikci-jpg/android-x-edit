/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.dateformatter.api.toHumanReadableDuration
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Slider
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.impl.util.bgCanvasWithTransparency
import io.element.android.libraries.ui.strings.CommonStrings
import timber.log.Timber

@Composable
fun MediaPlayerControllerView(
    state: MediaPlayerControllerState,
    onTogglePlay: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    audioFocus: AudioFocus?,
    modifier: Modifier = Modifier,
    onToggleResizeMode: () -> Unit = {},
    onSelectPlaybackSpeed: (Float) -> Unit = {},
    onToggleOrientationLock: () -> Unit = {},
    onCaptureFrame: () -> Unit = {},
    onEnterPictureInPicture: () -> Unit = {},
    onRotate: () -> Unit = {},
) {
    if (audioFocus != null) {
        val latestOnTogglePlay by rememberUpdatedState(onTogglePlay)
        LaunchedEffect(state.isPlaying) {
            if (state.isPlaying) {
                audioFocus.requestAudioFocus(
                    requester = AudioFocusRequester.MediaViewer,
                    onFocusLost = {
                        Timber.w("Audio focus lost")
                        latestOnTogglePlay()
                    },
                )
            } else {
                audioFocus.releaseAudioFocus()
            }
        }
    }

    AnimatedVisibility(
        visible = state.isVisible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .background(color = bgCanvasWithTransparency)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Two-row layout: primary playback (play + seek + time) on top,
            // secondary tools (mute / resize / speed / lock / snapshot / pip)
            // underneath. Modelled on Telegram's video player — keeps the
            // critical scrubbing controls full-width while the tools stay
            // discoverable without crowding the slider on narrow screens.
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bgColor = if (state.isPlaying) {
                        ElementTheme.colors.bgCanvasDefault
                    } else {
                        ElementTheme.colors.textPrimary
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = bgColor,
                                shape = CircleShape,
                            )
                            .clip(CircleShape)
                            .clickable { onTogglePlay() }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isPlaying) {
                            Icon(
                                imageVector = CompoundIcons.PauseSolid(),
                                tint = ElementTheme.colors.iconPrimary,
                                contentDescription = stringResource(CommonStrings.a11y_pause)
                            )
                        } else {
                            Icon(
                                imageVector = CompoundIcons.PlaySolid(),
                                tint = ElementTheme.colors.iconOnSolidPrimary,
                                contentDescription = stringResource(CommonStrings.a11y_play)
                            )
                        }
                    }
                    // Memoise the formatted progress string — the polling
                    // loop ticks 5x/s and toHumanReadableDuration is a
                    // String concatenation + locale-aware DurationFormatter
                    // call on every tick. remember() ensures we only
                    // re-format when the underlying ms actually changes,
                    // and within a second we typically tick the same ms
                    // bucket once (5 × 200 ms vs 1 s string resolution).
                    val formattedProgress = remember(state.displayProgressInMillis) {
                        state.displayProgressInMillis.toHumanReadableDuration()
                    }
                    Text(
                        modifier = Modifier
                            .widthIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        text = formattedProgress,
                        textAlign = TextAlign.Center,
                        color = ElementTheme.colors.textPrimary,
                        style = ElementTheme.typography.fontBodyXsMedium,
                    )
                    var lastSelectedValue by remember { mutableFloatStateOf(-1f) }
                    Slider(
                        modifier = Modifier.weight(1f),
                        valueRange = 0f..state.durationInMillis.toFloat(),
                        value = lastSelectedValue.takeIf { it >= 0 }
                            ?: state.seekingToMillis?.toFloat()
                            ?: state.progressInMillis.toFloat(),
                        onValueChange = {
                            lastSelectedValue = it
                        },
                        onValueChangeFinish = {
                            onSeekChange(lastSelectedValue)
                            lastSelectedValue = -1f
                        },
                        useCustomLayout = true,
                    )
                    val formattedDuration = remember(state.durationInMillis) {
                        state.durationInMillis.toHumanReadableDuration()
                    }
                    Text(
                        modifier = Modifier
                            .widthIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        text = formattedDuration,
                        textAlign = TextAlign.Center,
                        color = ElementTheme.colors.textPrimary,
                        style = ElementTheme.typography.fontBodyXsMedium,
                    )
                }
                // Tools row — evenly spaced across the row so each button has
                // breathing room (right-bunching looked cramped on narrow
                // screens, and the user explicitly asked for equal gaps).
                // Mute lives in this row too because it's a secondary toggle,
                // not a primary playback control.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Rotate the activity 90° on tap (toggles between
                    // portrait and landscape). Replaces the old volume
                    // mute button — audio focus and mute state are still
                    // tracked internally for the audio-focus listener,
                    // but the user found a dedicated mute button less
                    // useful here than a quick-rotate affordance.
                    IconButton(
                        onClick = onRotate,
                    ) {
                        Icon(
                            imageVector = CompoundIcons.RotateRight(),
                            tint = ElementTheme.colors.iconPrimary,
                            contentDescription = "Rotate",
                        )
                    }
                    IconButton(
                        onClick = onToggleResizeMode,
                    ) {
                        // Single icon that cycles through every resize
                        // mode (Original / 3:4 / 4:3 / 9:16 / 16:9 /
                        // FitScreen). The newly-active mode's label is
                        // surfaced as a brief on-video toast by
                        // MediaVideoView so the user knows what they
                        // just switched to.
                        Icon(
                            imageVector = CompoundIcons.Expand(),
                            tint = ElementTheme.colors.iconPrimary,
                            contentDescription = "Resize: ${state.resizeMode.label}",
                        )
                    }
                    PlaybackSpeedButton(
                        currentSpeed = state.playbackSpeed,
                        onSelectSpeed = onSelectPlaybackSpeed,
                    )
                    IconButton(
                        onClick = onToggleOrientationLock,
                    ) {
                        Icon(
                            imageVector = if (state.isOrientationLocked) CompoundIcons.LockSolid() else CompoundIcons.LockOff(),
                            tint = ElementTheme.colors.iconPrimary,
                            contentDescription = if (state.isOrientationLocked) "Unlock orientation" else "Lock orientation",
                        )
                    }
                    // Snapshot button moved out of the bottom tools row —
                    // now rendered as a dedicated top-end overlay by
                    // MediaVideoView so the camera affordance is reachable
                    // even when the bottom controller bar is hidden.
                    IconButton(
                        onClick = onEnterPictureInPicture,
                    ) {
                        Icon(
                            imageVector = CompoundIcons.PopOut(),
                            tint = ElementTheme.colors.iconPrimary,
                            contentDescription = "Minimise",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact text button showing the current playback rate ("1×", "1.5×", ...).
 * Tap opens a Material dropdown anchored beneath the button with the full
 * [PLAYBACK_SPEEDS] list. Picked it over a full-screen bottom sheet because
 * the controller bar is the user's focus and a sheet would obscure the video
 * they're trying to scrub.
 */
@Composable
private fun PlaybackSpeedButton(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = currentSpeed.formatPlaybackSpeed(),
                color = ElementTheme.colors.textPrimary,
                style = ElementTheme.typography.fontBodySmMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PLAYBACK_SPEEDS.forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = speed.formatPlaybackSpeed(),
                            style = ElementTheme.typography.fontBodyMdMedium,
                            color = if (speed == currentSpeed) {
                                ElementTheme.colors.textActionAccent
                            } else {
                                ElementTheme.colors.textPrimary
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectSpeed(speed)
                    },
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun MediaPlayerControllerViewPreview(
    @PreviewParameter(MediaPlayerControllerStateProvider::class) state: MediaPlayerControllerState
) = ElementPreview {
    MediaPlayerControllerView(
        state = state,
        onTogglePlay = {},
        onSeekChange = {},
        onToggleMute = {},
        audioFocus = null,
    )
}
