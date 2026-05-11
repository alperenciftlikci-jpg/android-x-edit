/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.last.tools.crop

import android.net.Uri
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.yalantis.ucrop.view.UCropView
import io.element.android.libraries.core.perf.TracedGesture
import java.io.File

// uCrop default decode size cap. 2048 covers screens up to 1440 px wide with comfortable
// pinch-zoom headroom while keeping the bitmap at ~16 MB instead of the 30–50 MB a raw camera
// shot would land at — that's what makes pan/zoom run at 60 fps instead of 45.
private const val UCROP_MAX_BITMAP_SIZE = 2048

/**
 * Crop tool backed by uCrop's `UCropView` (which composes a `GestureCropImageView` + an
 * `OverlayView`). Embedded inline via `AndroidView` — uCrop's typical Activity-based flow is
 * bypassed because we want the editor to remain a single screen.
 *
 * Touch start/end is observed with a `setOnTouchListener` so we can wrap each gesture as
 * `imageeditor.last.crop.gesture` in the perf dashboard. The listener returns `false` so the
 * underlying `GestureCropImageView` still gets the events.
 */
@Composable
fun LastUCropTool(
    sourceUri: Uri,
    rotationDegreesAdditive: Int,
    aspectRatio: Float?,
    modifier: Modifier = Modifier,
    onUCropViewReady: (UCropView) -> Unit = {},
) {
    val context = LocalContext.current
    val outputUri = remember(sourceUri) {
        Uri.fromFile(File(context.cacheDir, "ucrop-last-output-${System.currentTimeMillis()}.jpg"))
    }
    val gesture = remember { TracedGesture("imageeditor.last.crop.gesture") }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            UCropView(ctx, null).also { view ->
                // Cap the bitmap uCrop holds for pan/zoom — must be set before setImageUri.
                view.cropImageView.setMaxBitmapSize(UCROP_MAX_BITMAP_SIZE)
                view.cropImageView.setImageUri(sourceUri, outputUri)
                view.overlayView.setShowCropFrame(true)
                view.overlayView.setShowCropGrid(true)
                view.overlayView.setFreestyleCropEnabled(true)
                // UCropView is a FrameLayout with two stacked children — GestureCropImageView
                // (image pan/zoom) and OverlayView (crop-rect handles). A given touch only
                // lands on one. Register the same listener on both so we always observe gestures.
                val touchListener = View.OnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> gesture.start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture.finish()
                    }
                    false
                }
                view.cropImageView.setOnTouchListener(touchListener)
                view.overlayView.setOnTouchListener(touchListener)
                onUCropViewReady(view)
            }
        },
        update = { view ->
            if (aspectRatio != null && aspectRatio > 0f) {
                view.cropImageView.targetAspectRatio = aspectRatio
            } else {
                view.cropImageView.setTargetAspectRatio(0f)
            }
        },
    )

    LaunchedEffect(rotationDegreesAdditive) {
        // Caller drives postRotate via onUCropViewReady.cropImageView.
    }
}
