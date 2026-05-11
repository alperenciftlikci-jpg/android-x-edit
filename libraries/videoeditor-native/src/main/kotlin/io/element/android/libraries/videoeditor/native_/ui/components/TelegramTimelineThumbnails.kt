/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Horizontal strip of evenly-spaced video frame thumbnails. Same look as the strip you see
 * underneath Telegram's video trim slider. Width is filled by the parent; thumbnails are
 * stretched to share the available width equally so the strip is always visually
 * continuous regardless of how many frames the extractor produced.
 *
 * Thumbnails are produced asynchronously by the state holder via NativeVideoDecoder; this
 * component just renders whatever's currently in the list. Empty list ⇒ a black placeholder
 * row (during initial load).
 */
@Composable
fun TelegramTimelineThumbnails(
    frames: SnapshotStateList<Bitmap>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF1C1C1E)),
    ) {
        if (frames.isEmpty()) {
            // Loading placeholder — solid dark grey, reads "loading…" implicitly because
            // the trim handles haven't appeared yet either.
            return
        }
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            // RowScope's `weight(1f)` distributes width equally — same number of thumbnails
            // regardless of strip width, each cell `1/n` of the row.
            frames.forEach { bm ->
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}
