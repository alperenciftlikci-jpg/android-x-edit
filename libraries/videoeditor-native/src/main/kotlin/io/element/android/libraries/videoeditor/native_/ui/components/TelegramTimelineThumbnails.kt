/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Horizontal strip of evenly-spaced video frame thumbnails — same look as Telegram's video
 * trim slider. Width is filled by the parent; cells share width equally so the strip stays
 * visually continuous regardless of how many frames have arrived.
 *
 * While the extractor is still working we keep `expectedCount` cells in place:
 * already-decoded frames on the left, gently pulsing dark placeholders on the right.
 * This mirrors Telegram (cells pop in left-to-right) and gives the user immediate feedback
 * instead of a black bar that reads as broken.
 */
@Composable
fun TelegramTimelineThumbnails(
    frames: SnapshotStateList<Bitmap>,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    expectedCount: Int = 12,
) {
    val placeholders = (expectedCount - frames.size).coerceAtLeast(0)
    val infinite = rememberInfiniteTransition(label = "thumb-shimmer")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thumb-shimmer-alpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF1C1C1E)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            frames.forEach { bm ->
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            repeat(placeholders) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 0.5.dp)
                        .background(Color.White.copy(alpha = pulseAlpha)),
                )
            }
        }
    }
}
