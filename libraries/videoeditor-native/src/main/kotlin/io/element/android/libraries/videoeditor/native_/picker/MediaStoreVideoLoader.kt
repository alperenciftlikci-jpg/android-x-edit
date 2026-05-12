/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.videoeditor.native_.picker

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Query the device's video library directly from MediaStore. Returns entries sorted by
 * date-added DESC (newest first), with the real filesystem path filled in from the
 * `_data` column.
 *
 * Telegram's `MediaController#loadGalleryPhotosAlbums` uses the same query — that's
 * what gives them direct paths instead of FUSE-backed picker URIs.
 *
 * Callers must hold READ_MEDIA_VIDEO (API 33+) or READ_EXTERNAL_STORAGE (≤ 32);
 * the manifest in this module declares both. The runtime check is left to the picker
 * UI which knows how to prompt the user.
 */
suspend fun loadVideosFromMediaStore(context: Context): List<MediaStoreVideo> =
    withContext(Dispatchers.IO) {
        val out = ArrayList<MediaStoreVideo>(64)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null,
                sortOrder,
            )?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val idxData = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val idxDur = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val idxSize = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val idxDate = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idxId)
                    val name = cursor.getString(idxName) ?: continue
                    // Skip rows where _data is null or unreadable — happens on Android 14
                    // for scoped-storage protected files. Caller can still pick them
                    // via contentUri but the trim path won't be fast.
                    val path = if (idxData >= 0) cursor.getString(idxData) else null
                    if (path.isNullOrEmpty()) continue
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) continue

                    val duration = if (idxDur >= 0) cursor.getLong(idxDur) else 0L
                    val size = if (idxSize >= 0) cursor.getLong(idxSize) else file.length()
                    val date = if (idxDate >= 0) cursor.getLong(idxDate) * 1000L else 0L
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                    out.add(
                        MediaStoreVideo(
                            id = id,
                            displayName = name,
                            filePath = path,
                            contentUri = uri,
                            durationMs = duration,
                            sizeBytes = size,
                            dateAddedMs = date,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "loadVideosFromMediaStore failed")
        }
        Timber.d("loadVideosFromMediaStore: %d videos", out.size)
        out
    }
