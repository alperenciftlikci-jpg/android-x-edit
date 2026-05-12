/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.videoeditor.native_.picker

import android.net.Uri

/**
 * One video entry resolved from `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.
 *
 * The key bit for fast trim is [filePath] — a real filesystem path
 * (e.g. `/storage/emulated/0/DCIM/Camera/yolo.mp4`) read straight out of the
 * deprecated-but-still-functional `_data` column. Same trick Telegram uses to
 * bypass FUSE-backed PhotoPicker URIs.
 *
 * [contentUri] is also kept around so callers can still hand a `content://`
 * reference to APIs that require one (Coil thumbnails, share intents, etc.).
 */
data class MediaStoreVideo(
    val id: Long,
    val displayName: String,
    val filePath: String,
    val contentUri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedMs: Long,
)
