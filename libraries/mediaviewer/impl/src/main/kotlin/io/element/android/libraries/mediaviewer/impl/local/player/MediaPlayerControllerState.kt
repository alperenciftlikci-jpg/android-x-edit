/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.net.Uri
import androidx.annotation.FloatRange
import androidx.compose.ui.Alignment

/**
 * Which corner the in-app PiP mini player snaps to. Dragging the mini around
 * the screen and releasing snaps to the nearest corner — same behaviour as
 * YouTube's mini player and the standard "floating video" idiom on iOS.
 */
enum class MiniPlayerCorner {
    TOP_START, TOP_END, BOTTOM_START, BOTTOM_END;

    fun toAlignment(): Alignment = when (this) {
        TOP_START -> Alignment.TopStart
        TOP_END -> Alignment.TopEnd
        BOTTOM_START -> Alignment.BottomStart
        BOTTOM_END -> Alignment.BottomEnd
    }
}

data class MediaPlayerControllerState(
    val isVisible: Boolean,
    val isPlaying: Boolean,
    val isReady: Boolean,
    val progressInMillis: Long,
    val durationInMillis: Long,
    val canMute: Boolean,
    val isMuted: Boolean,
    val seekingToMillis: Long?,
    /** How the video frame is fitted into the player surface — see [VideoResizeMode].
     *  Default ORIGINAL preserves the video's native aspect with letterbox bars
     *  (same look as the old FIT default). */
    val resizeMode: VideoResizeMode = VideoResizeMode.ORIGINAL,
    /** Current playback rate, used both to drive ExoPlayer.setPlaybackSpeed and
     *  to render the "1×" label on the speed button. Must be a value from
     *  [PLAYBACK_SPEEDS] — the picker only ever surfaces those, but the field is
     *  Float (not enum) because ExoPlayer's API takes Float and round-tripping
     *  through an enum just to convert back was noise. */
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    /** Lock the Activity's orientation to whatever it was at lock-on time. The
     *  effect is set up by [OrientationLockEffect]; the state only carries the
     *  boolean so the UI button can render the right icon (Lock vs LockOff). */
    val isOrientationLocked: Boolean = false,
    /** Hides the snapshot button entirely when false. Set to false for
     *  image-only media items and during the brief window between snapshot
     *  press and write completion (the button doubles as a busy indicator). */
    val canCaptureFrame: Boolean = false,
    /** True from the moment the user taps Snapshot until the frame is written
     *  to the gallery. Used to disable the button + show a brief progress
     *  spinner so a double-tap doesn't queue two captures. */
    val isCapturingFrame: Boolean = false,
    /** Hides the PiP button on devices / OS versions that can't enter PiP. The
     *  capability is recomputed each composition by [rememberPictureInPictureState]
     *  and pushed into state at the call site. */
    val canEnterPictureInPicture: Boolean = false,
    /** True while the OS has us in a PiP window. Drives "hide all controls in
     *  PiP" — controls don't fit in a small window and the system already
     *  paints its own play/pause overlay via setActions. */
    val isInPictureInPicture: Boolean = false,
    /** True while the in-app mini-player is active: the player is shrunk to
     *  a floating window inside the current screen (no OS-level PiP). System
     *  PiP still kicks in automatically when the app actually goes to the
     *  background via auto-enter; this flag is purely the in-foreground
     *  "shrink to corner" mode the user explicitly toggles. */
    val isInAppPiP: Boolean = false,
    /** Which corner the mini player is currently anchored at. The user drags
     *  the mini around the screen; on release the closest of the four corners
     *  wins. Default bottom-end matches the YouTube / standard floating-video
     *  convention so first-time toggling lands where users already expect. */
    val miniPlayerCorner: MiniPlayerCorner = MiniPlayerCorner.BOTTOM_END,
    /** Toggles the overlay controls (play/pause + close + expand buttons)
     *  inside the mini player. Tap on the mini flips this; auto-hides after
     *  a few seconds while playing so the overlay doesn't sit over the video
     *  the whole time. */
    val miniControlsVisible: Boolean = true,
    /** The most recently saved snapshot's URI, surfaced as a brief preview
     *  overlay in the top-left so the user sees confirmation that the capture
     *  succeeded. The screen clears this back to null ~2 seconds after the
     *  capture completes; the UI animates a fade in / fade out either side
     *  of that. */
    val lastSnapshotUri: Uri? = null,
) {
    /**
     * The progress in milliseconds to display. When [seekingToMillis] is non-null (during a seek operation),
     * this returns the target seek position. Once the player catches up to the seek position,
     * [seekingToMillis] is cleared (set to null) and this returns [progressInMillis] again.
     */
    val displayProgressInMillis: Long
        get() = seekingToMillis ?: progressInMillis

    @FloatRange(from = 0.0, to = 1.0)
    val progressAsFloat = (displayProgressInMillis.toFloat() / durationInMillis.toFloat()).coerceIn(0f, 1f)
}
