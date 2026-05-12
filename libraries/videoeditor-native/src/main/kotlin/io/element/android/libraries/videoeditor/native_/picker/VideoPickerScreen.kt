/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.videoeditor.native_.picker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import io.element.android.libraries.videoeditor.native_.picker.MediaStoreVideo
import io.element.android.libraries.videoeditor.native_.picker.loadVideosFromMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PickerBackground = Color(0xFF000000)
private val PickerSecondary  = Color(0xFF1C1C1E)
private val PickerAccent     = Color(0xFFFFFF00) // matches the editor's yellow

/**
 * MediaStore-backed video picker, Telegram-style. Lists the user's gallery videos
 * with thumbnails and returns a [MediaStoreVideo] whose `filePath` field is a real
 * filesystem path (not a FUSE-backed PhotoPicker URI). Hand that path straight to
 * `Mp4TrimEngine.trim()` for a 2-4 second export instead of 30-40 seconds.
 *
 * Behaviour:
 *  - Asks for the appropriate read-media permission once (READ_MEDIA_VIDEO on API 33+
 *    or READ_EXTERNAL_STORAGE on older).
 *  - Loads videos asynchronously on the IO dispatcher.
 *  - Renders a 3-column grid; thumbnails are lazy-loaded per item via produceState.
 *  - Tap a video → `onPicked` fires with the entry. Caller navigates to the editor.
 */
@Composable
fun VideoPickerScreen(
    onCancel: () -> Unit,
    onPicked: (MediaStoreVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val requiredPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionAsked = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionAsked) {
            permissionLauncher.launch(requiredPermission)
        }
    }

    val videos = remember { mutableStateOf<List<MediaStoreVideo>?>(null) }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            videos.value = null // show loading state
            videos.value = loadVideosFromMediaStore(context)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PickerBackground)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar — ✕ + count
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, "Cancel", tint = Color.White)
                }
                Spacer(Modifier.size(8.dp))
                BasicText(
                    text = "Pick a video",
                    style = TextStyle(color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.weight(1f))
                videos.value?.let {
                    BasicText(
                        text = "${it.size} videos",
                        style = TextStyle(color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp),
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }

            when {
                !hasPermission && permissionAsked -> PermissionDeniedView(
                    onRetry = { permissionLauncher.launch(requiredPermission) }
                )
                !hasPermission -> LoadingView("Requesting permission…")
                videos.value == null -> LoadingView("Reading library…")
                videos.value!!.isEmpty() -> EmptyView()
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                ) {
                    items(videos.value!!, key = { it.id }) { entry ->
                        VideoCell(
                            entry = entry,
                            onClick = { onPicked(entry) },
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCell(
    entry: MediaStoreVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbBitmap by produceState<Bitmap?>(initialValue = null, key1 = entry.id) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, entry) }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(PickerSecondary)
            .clickable(onClick = onClick),
    ) {
        thumbBitmap?.let { bm ->
            Image(
                bitmap = bm.asImageBitmap(),
                contentDescription = entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Duration badge bottom-right
        if (entry.durationMs > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                BasicText(
                    text = formatDuration(entry.durationMs),
                    style = TextStyle(color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun LoadingView(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = PickerAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(10.dp))
            BasicText(
                text = label,
                style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp),
            )
        }
    }
}

@Composable
private fun EmptyView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
            text = "No videos on this device.",
            style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp),
        )
    }
}

@Composable
private fun PermissionDeniedView(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "Permission needed to read videos.",
                style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp),
            )
            Spacer(Modifier.size(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(PickerAccent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                BasicText(
                    text = "Grant",
                    style = TextStyle(color = Color.Black, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * Load a thumbnail for a video entry. Tries `ContentResolver.loadThumbnail` on
 * API 29+ first (it's fast and cached system-side); falls back to
 * MediaMetadataRetriever for older devices.
 */
private fun loadThumbnail(
    context: android.content.Context,
    entry: MediaStoreVideo,
): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(entry.contentUri, Size(256, 256), null)
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(entry.filePath)
                mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { mmr.release() }
            }
        }
    } catch (_: Throwable) {
        null
    }
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
