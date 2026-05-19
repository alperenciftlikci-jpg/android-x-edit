/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Lock the host Activity's orientation to whatever the screen is currently
 * showing, for the lifetime of this composable. The "current orientation"
 * read is intentionally captured at lock-on time, so flipping the lock on in
 * portrait and rotating the phone afterwards still keeps the player upright.
 *
 * Cleanup is critical: failing to restore [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED]
 * on dispose would freeze the rest of the app in whatever orientation the user
 * last locked the player to. The DisposableEffect handles both the on/off
 * toggle (key change) and the unmount path.
 */
@Composable
fun OrientationLockEffect(locked: Boolean) {
    // LocalActivity (activity-compose 1.10+) gives us the host Activity
    // directly, no ContextWrapper-walk required. The walker still lives in
    // findActivity() below because other call sites depend on it from
    // non-composable code paths.
    val activity = LocalActivity.current
    DisposableEffect(locked) {
        if (locked && activity != null) {
            // Only force SCREEN_ORIENTATION_LOCKED if the activity is
            // currently in a sensor-driven mode. If the user just hit
            // Rotate (which sets a specific PORTRAIT / LANDSCAPE /
            // REVERSE_* orientation) the activity is already effectively
            // locked to that orientation, and overwriting with LOCKED
            // here would race against the in-flight rotation and could
            // freeze us at the *previous* orientation — exactly the
            // "lock open but screen doesn't rotate" bug the user
            // reported. Letting the explicit orientation stand keeps
            // the rotate-then-lock sequence consistent.
            val current = activity.requestedOrientation
            val sensorBased = current == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
                current == ActivityInfo.SCREEN_ORIENTATION_SENSOR ||
                current == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR ||
                current == ActivityInfo.SCREEN_ORIENTATION_USER ||
                current == ActivityInfo.SCREEN_ORIENTATION_FULL_USER ||
                current == ActivityInfo.SCREEN_ORIENTATION_BEHIND
            if (sensorBased) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }
        }
        onDispose {
            // Whenever the user toggles lock OFF — or this composable
            // leaves the tree — restore UNSPECIFIED so the device
            // follows the user's system rotation setting again. This is
            // what guarantees "tap lock off → rotation works" even
            // after an earlier explicit Rotate-button choice.
            if (locked && activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
