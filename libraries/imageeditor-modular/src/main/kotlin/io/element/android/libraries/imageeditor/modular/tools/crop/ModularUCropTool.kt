/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.modular.tools.crop

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

// Longest side cap for the bitmap uCrop loads into the GestureCropImageView. 2048 keeps a
// comfortable headroom over typical phone screens (≤1440 px wide) so pinch-zoom doesn't show
// pixellation, while keeping the bitmap at ~16 MB instead of the 30–50 MB a raw camera shot
// would land at — that's what makes pan/zoom run at 60 fps instead of 45.
private const val UCROP_MAX_BITMAP_SIZE = 2048

/**
 * Crop tool backed by uCrop's `UCropView` (which composes a `GestureCropImageView` + `OverlayView`).
 * Embedded inline in Compose via `AndroidView` — uCrop's typical Activity-based flow is bypassed
 * because we want the editor to remain a single screen.
 *
 * Touch start/end is observed with a `setOnTouchListener` so we can wrap each gesture as
 * `imageeditor.modular.crop.gesture` in the perf dashboard. The listener returns `false` so the
 * underlying `GestureCropImageView` still gets the events.
 */
@Composable
fun ModularUCropTool(
    sourceUri: Uri,
    rotationDegreesAdditive: Int,
    aspectRatio: Float?,
    modifier: Modifier = Modifier,
    onUCropViewReady: (UCropView) -> Unit = {},
) {
    val context = LocalContext.current
    val outputUri = remember(sourceUri) {
        Uri.fromFile(File(context.cacheDir, "ucrop-output-${System.currentTimeMillis()}.jpg"))
    }
    val gesture = remember { TracedGesture("imageeditor.modular.crop.gesture") }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            UCropView(ctx, null).also { view ->
                // Cap the bitmap uCrop holds for pan/zoom. Without this, uCrop decodes the source
                // at native resolution (a 12 MP camera shot is 4032×3024 → 48 MB ARGB_8888). Pan
                // and pinch-zoom transform that bitmap with a Matrix every frame, which on
                // mid-tier devices drops the gesture to ~45 fps. The cap is set *before*
                // setImageUri, otherwise it has no effect on the initial decode.
                view.cropImageView.setMaxBitmapSize(UCROP_MAX_BITMAP_SIZE)
                view.cropImageView.setImageUri(sourceUri, outputUri)
                view.overlayView.setShowCropFrame(true)
                view.overlayView.setShowCropGrid(true)
                view.overlayView.setFreestyleCropEnabled(true)
                // uCrop's UCropView is a FrameLayout with two stacked children:
                //  - GestureCropImageView (handles pan/zoom of the underlying image)
                //  - OverlayView (handles dragging of the crop-rect corner handles, on top)
                // A given touch only ever lands on ONE of them. To catch every gesture
                // (corner-handle drag *and* image pan/zoom) we register the same listener on
                // both views — the one receiving the touch fires our TracedGesture, the other
                // stays silent. Returning false lets uCrop's own touch processing run normally.
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
            // Aspect ratio: 0f means free-form (no constraint).
            if (aspectRatio != null && aspectRatio > 0f) {
                view.cropImageView.targetAspectRatio = aspectRatio
            } else {
                view.cropImageView.setTargetAspectRatio(0f)
            }
        },
    )

    // Apply incremental rotation when the editor's rotationDegrees changes. uCrop's
    // postRotate is delta-based, so we rotate by 90° each time the multiple-of-90 changes.
    LaunchedEffect(rotationDegreesAdditive) {
        // No-op — caller drives rotation via onUCropViewReady.cropImageView.postRotate()
        // in their own state observer. Keeping this hook here in case we want to migrate.
    }
}
