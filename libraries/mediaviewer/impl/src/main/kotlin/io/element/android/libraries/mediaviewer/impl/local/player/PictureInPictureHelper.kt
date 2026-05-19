/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber

/**
 * Picture-in-Picture support. PiP needs cooperation from three sides:
 *  1. The Activity must declare `android:supportsPictureInPicture="true"` in
 *     the manifest. The helper checks the manifest declaration at runtime via
 *     PackageManager so a missing entry surfaces as a no-op + log rather than
 *     a crash on the user's first tap.
 *  2. The composable must hook the Activity's `onPictureInPictureModeChanged`
 *     callback to know when the system shrunk us into a PiP window — that's
 *     what drives "hide controls in PiP mode" UI behaviour.
 *  3. The video aspect ratio must be passed through `PictureInPictureParams`
 *     so the PiP window matches the video shape (otherwise the OS picks a
 *     square and the video sits in a tiny letterboxed strip).
 *
 * API 26+ only (PiP isn't available before Oreo). On older OSes the entry
 * point composable [rememberPictureInPictureState] returns `available = false`
 * and the UI hides the button accordingly.
 */
class PictureInPictureState internal constructor(
    val available: Boolean,
    internal val onEnter: ((Rational) -> Unit)?,
    val isInPiP: MutableState<Boolean>,
)

@Composable
fun rememberPictureInPictureState(): PictureInPictureState {
    val context = LocalContext.current
    val isInPiP = remember { mutableStateOf(false) }
    val available = remember(context) { isPictureInPictureSupported(context) }
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh isInPiP from the Activity at every lifecycle transition. PiP
    // entry / exit always fire ON_PAUSE / ON_RESUME on the activity (the
    // system pauses the activity when it goes into PiP and resumes it when
    // the user expands or dismisses), so this catches both directions
    // without needing the androidx.activity-specific listener API.
    DisposableEffect(lifecycleOwner, activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onDispose { /* nothing wired */ }
        } else {
            // Initial sync in case we entered the composable while already in PiP.
            isInPiP.value = activity.isInPictureInPictureMode
            val observer = LifecycleEventObserver { _, _ ->
                isInPiP.value = activity.isInPictureInPictureMode
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    return PictureInPictureState(
        available = available,
        isInPiP = isInPiP,
        onEnter = if (available && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            { aspect -> enterPiP(context, aspect) }
        } else {
            null
        },
    )
}

@RequiresApi(Build.VERSION_CODES.O)
private fun enterPiP(context: Context, aspect: Rational) {
    val activity = context.findActivity() ?: return
    // PiP windows clamp the aspect ratio to [1:2.39, 2.39:1] — anything wider
    // gets letterboxed inside the PiP frame regardless of what we ask for.
    // Coerce here so a 21:9 movie still hits the closest legal aspect rather
    // than throwing IllegalArgumentException at the OS boundary.
    val safeAspect = aspect.coerceToPiPRange()
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(safeAspect)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Auto-enter PiP when the user hits Home — only on Android 12+,
                // OS-level behaviour. The user still has to enable PiP for the
                // app once in Settings > Apps > Special access; the manifest
                // declaration only grants the *capability*.
                setAutoEnterEnabled(true)
            }
        }
        .build()
    try {
        activity.enterPictureInPictureMode(params)
    } catch (t: Throwable) {
        Timber.w(t, "enterPictureInPictureMode failed (manifest declaration missing?)")
    }
}

private fun isPictureInPictureSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val pm = context.packageManager
    val supportsFeature = pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    if (!supportsFeature) return false
    // Even if the device supports PiP, the manifest entry on our Activity must
    // opt in — otherwise enterPictureInPictureMode throws. Check the activity
    // metadata so the button stays hidden if the wiring isn't there.
    val activity = context.findActivity() ?: return false
    val info = try {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(activity.componentName, PackageManager.ComponentInfoFlags.of(0L))
        } else {
            pm.getActivityInfo(activity.componentName, 0)
        }
    } catch (t: Throwable) {
        Timber.w(t, "PiP: could not read ActivityInfo")
        return false
    }
    // ActivityInfo.flagsSupportsPictureInPicture (0x400000) flips on when the
    // manifest says supportsPictureInPicture="true".
    return (info.flags and FLAG_SUPPORTS_PICTURE_IN_PICTURE) != 0
}

private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x00400000

@RequiresApi(Build.VERSION_CODES.O)
private fun Rational.coerceToPiPRange(): Rational {
    val min = Rational(100, 239)  // ≈ 1:2.39
    val max = Rational(239, 100)  // ≈ 2.39:1
    return when {
        this.toFloat() < min.toFloat() -> min
        this.toFloat() > max.toFloat() -> max
        else -> this
    }
}

/**
 * Proactively set PictureInPictureParams on the host Activity with
 * `autoEnterEnabled = true` so backgrounding (home press, recents) on
 * Android 12+ shrinks straight into PiP with the right aspect.
 *
 * `sourceRectHint` is critical for the visual fix the user asked for: it
 * tells the OS "only this rectangle of my window is the actual content —
 * crop / zoom the PiP window to that rect, ignore the surrounding chrome".
 * Without it, system PiP captures the whole activity (composer, headers,
 * everything) and squeezes it into the PiP window — that's why the user
 * was seeing the message composer inside their PiP screenshot. With the
 * hint set to the video player's screen rect, PiP captures only the video.
 *
 * Cleanup matters: on dispose we reset autoEnter back to false so other
 * screens (no video on display) don't get accidentally PiP-ed when the
 * user backgrounds the app. Without this, the params persist on the
 * Activity forever — once any video was watched, every subsequent
 * backgrounding from anywhere in the app would trigger PiP, which is
 * exactly the "PiP keeps firing without a video" bug the user flagged.
 */
@Composable
fun AutoEnterPictureInPictureEffect(
    aspectRatio: Rational?,
    sourceRectHint: Rect? = null,
    isPlaying: Boolean = false,
    onPlayPause: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onSkipBack: (() -> Unit)? = null,
    onSkipForward: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    // Action receiver: PendingIntents from setActions broadcast into here,
    // we route to the appropriate callback. Registered once per Activity
    // lifetime; the action key inside the intent extras tells us which
    // button the user pressed in the system PiP overlay.
    DisposableEffect(context, onPlayPause, onBack, onSkipBack, onSkipForward) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            (onPlayPause == null && onBack == null && onSkipBack == null && onSkipForward == null)) {
            return@DisposableEffect onDispose { /* nothing to wire */ }
        }
        val activity = context.findActivity()
            ?: return@DisposableEffect onDispose { /* no activity */ }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra(PIP_ACTION_KEY)) {
                    PIP_ACTION_PLAY_PAUSE -> onPlayPause?.invoke()
                    PIP_ACTION_BACK -> onBack?.invoke()
                    PIP_ACTION_SKIP_BACK -> onSkipBack?.invoke()
                    PIP_ACTION_SKIP_FORWARD -> onSkipForward?.invoke()
                }
            }
        }
        val filter = IntentFilter(PIP_ACTION_INTENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                activity.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                /* receiver wasn't registered — ignore */
            }
        }
    }

    LaunchedEffect(
        aspectRatio, sourceRectHint, isPlaying,
        onPlayPause != null, onBack != null, onSkipBack != null, onSkipForward != null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        if (aspectRatio == null) return@LaunchedEffect
        val activity = context.findActivity() ?: return@LaunchedEffect
        if (!isPictureInPictureSupported(context)) return@LaunchedEffect
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio.coerceToPiPRange())
            .setAutoEnterEnabled(true)
            .apply {
                if (sourceRectHint != null && !sourceRectHint.isEmpty) {
                    setSourceRectHint(sourceRectHint)
                }
                val actions = buildPipActions(
                    activity, isPlaying, onPlayPause, onBack, onSkipBack, onSkipForward,
                )
                if (actions.isNotEmpty()) {
                    setActions(actions)
                }
            }
            .build()
        try {
            activity.setPictureInPictureParams(params)
        } catch (t: Throwable) {
            Timber.w(t, "setPictureInPictureParams failed")
        }
    }
    DisposableEffect(context) {
        onDispose {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@onDispose
            val activity = context.findActivity() ?: return@onDispose
            if (!isPictureInPictureSupported(context)) return@onDispose
            val params = PictureInPictureParams.Builder()
                .setAutoEnterEnabled(false)
                .build()
            try {
                activity.setPictureInPictureParams(params)
            } catch (t: Throwable) {
                Timber.w(t, "setPictureInPictureParams cleanup failed")
            }
        }
    }
}

/** Compose-side broadcast intent action + extras the PiP RemoteActions fire. */
private const val PIP_ACTION_INTENT = "io.element.android.mediaviewer.pip.ACTION"
private const val PIP_ACTION_KEY = "io.element.android.mediaviewer.pip.KEY"
private const val PIP_ACTION_PLAY_PAUSE = "play_pause"
private const val PIP_ACTION_BACK = "back"
private const val PIP_ACTION_SKIP_BACK = "skip_back"
private const val PIP_ACTION_SKIP_FORWARD = "skip_forward"
private const val PIP_REQUEST_BACK = 1001
private const val PIP_REQUEST_PLAY_PAUSE = 1002
private const val PIP_REQUEST_SKIP_BACK = 1003
private const val PIP_REQUEST_SKIP_FORWARD = 1004

@RequiresApi(Build.VERSION_CODES.O)
private fun buildPipActions(
    activity: Activity,
    isPlaying: Boolean,
    onPlayPause: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onSkipBack: (() -> Unit)?,
    onSkipForward: (() -> Unit)?,
): List<RemoteAction> {
    val list = mutableListOf<RemoteAction>()
    // PendingIntents need FLAG_UPDATE_CURRENT so re-building actions
    // (play→pause toggle) swaps the underlying intent's icon instead of
    // re-using the cached stale one. Android 12+ also forces FLAG_IMMUTABLE
    // for broadcasts.
    val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0

    // YouTube-style 3-action layout: skip-back / play-pause / skip-forward.
    // Critical UI reason for filling all three slots: Android shows a
    // centred "[_]" PiP menu hint when the action row has empty slots.
    // Three actions evict that hint entirely and leave a clean
    // skip / play / skip strip in the middle of the PiP window.
    if (onSkipBack != null) {
        val intent = Intent(PIP_ACTION_INTENT)
            .setPackage(activity.packageName)
            .putExtra(PIP_ACTION_KEY, PIP_ACTION_SKIP_BACK)
        val pi = PendingIntent.getBroadcast(activity, PIP_REQUEST_SKIP_BACK, intent, piFlags)
        list += RemoteAction(
            Icon.createWithResource(
                activity,
                io.element.android.libraries.mediaviewer.impl.R.drawable.ic_skip_back_10,
            ),
            "Skip back 10s",
            "Skip back 10 seconds",
            pi,
        )
    }
    if (onPlayPause != null) {
        val intent = Intent(PIP_ACTION_INTENT)
            .setPackage(activity.packageName)
            .putExtra(PIP_ACTION_KEY, PIP_ACTION_PLAY_PAUSE)
        val pi = PendingIntent.getBroadcast(activity, PIP_REQUEST_PLAY_PAUSE, intent, piFlags)
        val iconRes = if (isPlaying) {
            io.element.android.compound.R.drawable.ic_compound_pause_solid
        } else {
            io.element.android.compound.R.drawable.ic_compound_play_solid
        }
        list += RemoteAction(
            Icon.createWithResource(activity, iconRes),
            if (isPlaying) "Pause" else "Play",
            if (isPlaying) "Pause video" else "Play video",
            pi,
        )
    }
    if (onSkipForward != null) {
        val intent = Intent(PIP_ACTION_INTENT)
            .setPackage(activity.packageName)
            .putExtra(PIP_ACTION_KEY, PIP_ACTION_SKIP_FORWARD)
        val pi = PendingIntent.getBroadcast(activity, PIP_REQUEST_SKIP_FORWARD, intent, piFlags)
        list += RemoteAction(
            Icon.createWithResource(
                activity,
                io.element.android.libraries.mediaviewer.impl.R.drawable.ic_skip_forward_10,
            ),
            "Skip forward 10s",
            "Skip forward 10 seconds",
            pi,
        )
    }
    // `onBack` is intentionally unused here — tapping anywhere in the PiP
    // window already expands back to the activity, so a dedicated back
    // action duplicated that affordance and pushed one of the more useful
    // skip / play actions out of the 3-slot limit.
    @Suppress("UNUSED_PARAMETER")
    onBack
    return list
}

/**
 * Pulls the playing video's aspect ratio from the ExoPlayer's last reported
 * VideoSize. Returns a sensible 16:9 fallback when no video has loaded yet,
 * so the first tap on the PiP button doesn't error out on an unknown aspect.
 */
fun ExoPlayer.videoAspectRatio(): Rational {
    val videoSize = (this as Player).videoSize
    if (videoSize.width <= 0 || videoSize.height <= 0) {
        return Rational(16, 9)
    }
    return Rational(videoSize.width, videoSize.height)
}
