/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui.state

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.element.android.libraries.videoeditor.native_.VideoMetadata

/**
 * State holder for the Telegram-style video trimmer.
 *
 *  - `metadata`: source video info loaded once on init via MediaMetadataRetriever.
 *  - `thumbnails`: a SnapshotStateList of evenly-spaced frame previews; populated
 *    asynchronously by the screen from MediaMetadataRetriever (hardware-accelerated).
 *  - `trimStart` / `trimEnd`: 0..1 normalised positions over the full duration.
 *    Convert to ms via `state.trimStartMs / state.trimEndMs`.
 */
@Stable
class VideoEditorProState {
    var metadata: VideoMetadata by mutableStateOf(VideoMetadata(0, 0, 0L, 0f, 0, false))
        internal set

    val thumbnails: SnapshotStateList<Bitmap> = mutableStateListOf()

    var trimStart: Float by mutableStateOf(0f)
    var trimEnd: Float   by mutableStateOf(1f)

    var isExporting: Boolean by mutableStateOf(false)
        internal set
    var exportProgress: Float by mutableStateOf(0f)
        internal set

    // Playback state — ExoPlayer-driven.
    var isPlaying: Boolean by mutableStateOf(false)
    var currentPlaybackMs: Long by mutableStateOf(0L)

    val trimStartMs: Float get() = trimStart * metadata.durationMs
    val trimEndMs:   Float get() = trimEnd   * metadata.durationMs
}

@Composable
fun rememberVideoEditorProState(): VideoEditorProState =
    remember { VideoEditorProState() }
