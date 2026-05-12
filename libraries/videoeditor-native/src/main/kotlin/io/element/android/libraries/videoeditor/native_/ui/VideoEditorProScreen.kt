/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.OptIn as MediaOptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.element.android.libraries.videoeditor.native_.NativeStreamCopyTrim
import io.element.android.libraries.videoeditor.native_.VideoMetadata
import io.element.android.libraries.videoeditor.native_.mp4.Mp4TrimEngine
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramTimelineThumbnails
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramTrimHandles
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramVideoPlayer
import io.element.android.libraries.videoeditor.native_.ui.state.VideoEditorProState
import io.element.android.libraries.videoeditor.native_.ui.state.rememberVideoEditorProState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

// Match `org.telegram.ui.Components.VideoTimelinePlayView` paints — pure yellow on near-
// black, lifted by a slightly lighter secondary for the bottom panel.
private val TelegramAccent = Color(0xFFFFFF00)
private val TelegramBackground = Color(0xFF000000)
private val TelegramSecondary = Color(0xFF1C1C1E)

private const val THUMB_COUNT = 12
private const val THUMB_WIDTH = 96
private const val THUMB_HEIGHT = 54

// Trim implementation switch. Flip to compare:
//   - JAVA_MP4BUILDER (default): Telegram's verbatim Java code (Mp4TrimEngine).
//   - CPP_FFMPEG:                 FFmpeg-based stream copy in C++ (NativeStreamCopyTrim).
// The C++ path requires libvideoedit.so on the target ABI (armeabi-v7a + x86_64 only
// today). On arm64 it'll throw UnsatisfiedLinkError; we catch that and fall back.
private enum class TrimImpl { JAVA_MP4BUILDER, CPP_FFMPEG }
private val TRIM_IMPL: TrimImpl = TrimImpl.CPP_FFMPEG

/**
 * Telegram-style video trimmer — MMR for thumbnails, stream-copy for export.
 *
 *   ┌─────────────────────────────────┐
 *   │ ✕                  ▶/⏸      ✓  │
 *   ├─────────────────────────────────┤
 *   │                                 │
 *   │     [PlayerView]                │  ← ExoPlayer preview
 *   │                                 │
 *   ├─────────────────────────────────┤
 *   │ 0:00.0                  0:12.4  │
 *   │ [thumbnails ▮▮▮▮▮▮▮▮▮▮▮]  ←|  │  ← timeline + handles + play-head
 *   │ Selected: 0:08.2                │
 *   └─────────────────────────────────┘
 *
 * No file copy on open (MediaMetadataRetriever accepts content:// directly), no transcode
 * on export (MediaExtractor+MediaMuxer stream-copy samples between trim bounds). Trim is
 * keyframe-aligned — the actual start jumps to the nearest sync sample at or before the
 * requested start, which is Telegram's "fast trim" behaviour.
 */
@Composable
fun VideoEditorProScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = rememberVideoEditorProState()
    val scope = rememberCoroutineScope()
    var applyError by remember { mutableStateOf<String?>(null) }
    var sourceLoadFailed by remember { mutableStateOf(false) }
    // Pre-copy state: the FUSE-backed content URI is copied to internal cache on the side
    // so the eventual export can read from ext4 (~3x faster end-to-end). Sized roughly to
    // the user's interaction time — by the time they finish trimming the file is usually
    // ready and ✓ exports in ~12 s instead of ~27 s.
    var localSource by remember { mutableStateOf<File?>(null) }
    var copyJob by remember { mutableStateOf<Job?>(null) }
    // Scrub command for the player. Each call to the play-head callback writes a fresh ms
    // value here; TelegramVideoPlayer observes it via LaunchedEffect and issues seekTo.
    var scrubRequestMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(sourceUri) {
        Timber.d("VideoEditorProScreen: loading metadata + thumbnails for %s", sourceUri)
        // (A) Fast: MMR thumbnails + metadata, populated as they arrive.
        scope.launch(Dispatchers.IO) {
            val loaded = loadMetadataAndThumbnails(
                context = context,
                uri = sourceUri,
                count = THUMB_COUNT,
                onMetadata = { md -> state.metadata = md },
                onThumbnail = { bm -> state.thumbnails.add(bm) },
            )
            if (!loaded) {
                Timber.e("VideoEditorProScreen: MMR load failed for %s", sourceUri)
                sourceLoadFailed = true
            }
        }
        // (B) Slow: pre-copy of the content:// source to internal cache. Saves the export
        // path from ~14 k FUSE syscalls × ~200 µs each by amortising into one sequential
        // sendfile() transfer. Runs in parallel; export waits up to 5 s if not done yet.
        if (sourceUri.scheme == "content") {
            copyJob = scope.launch(Dispatchers.IO) {
                val tStart = SystemClock.elapsedRealtime()
                val cached = preCopyContentToCache(context, sourceUri)
                if (cached != null) {
                    localSource = cached
                    Timber.d("pre-copy done: %d bytes in %d ms",
                        cached.length(), SystemClock.elapsedRealtime() - tStart)
                }
            }
        }
    }

    DisposableEffect(sourceUri) {
        onDispose {
            copyJob?.cancel()
            runCatching { localSource?.delete() }
        }
    }

    BackHandler(enabled = !state.isExporting, onBack = onCancel)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TelegramBackground)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Top bar — Telegram video editor mirror: ✕ left, play/pause + ✓ right, no title.
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarIconButton(Icons.Filled.Close, "Cancel", Color.White, onClick = onCancel)
                Spacer(Modifier.weight(1f))
                TopBarIconButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    onClick = { state.isPlaying = !state.isPlaying },
                )
                Spacer(Modifier.size(4.dp))
                TopBarIconButton(
                    icon = Icons.Filled.Check,
                    contentDescription = "Apply",
                    tint = TelegramAccent,
                    enabled = !state.isExporting && state.metadata.durationMs > 0,
                    onClick = {
                        if (state.isExporting) return@TopBarIconButton
                        if (state.metadata.durationMs <= 0) {
                            applyError = "Source video not loaded yet."
                            return@TopBarIconButton
                        }
                        state.isExporting = true
                        state.exportProgress = 0f
                        state.isPlaying = false
                        applyError = null
                        scope.launch {
                            // Give the background pre-copy up to 5 s extra to finish — if
                            // it's nearly done that's well worth waiting; if it's barely
                            // started we fall through and read the source URI directly.
                            withTimeoutOrNull(5_000) { copyJob?.join() }

                            // Try the fast path first (Telegram's ported MP4Builder, runs
                            // ~2-3 s for a 3 min clip). If it can't handle the source —
                            // unsupported codec, audio-only, missing csd — fall back to
                            // Media3 Transformer which works on every input but takes
                            // ~5-8 s. Either way: universal device support, best speed
                            // achievable for the input.
                            var outcome = runMp4BuilderTrim(
                                context = context,
                                sourceUri = sourceUri,
                                localSource = localSource,
                                trimStartMs = state.trimStartMs.toLong(),
                                trimEndMs = state.trimEndMs.toLong(),
                                onProgress = { state.exportProgress = it },
                            )
                            if (outcome is VideoApplyOutcome.Error) {
                                Timber.d("Fast path failed (%s), falling back to Transformer",
                                    outcome.message)
                                state.exportProgress = 0f
                                outcome = runStreamCopyTrim(
                                    context = context,
                                    sourceUri = sourceUri,
                                    localSource = localSource,
                                    trimStartMs = state.trimStartMs.toLong(),
                                    trimEndMs = state.trimEndMs.toLong(),
                                    onProgress = { state.exportProgress = it },
                                )
                            }
                            state.isExporting = false
                            when (outcome) {
                                is VideoApplyOutcome.Success -> onConfirm(outcome.uri)
                                is VideoApplyOutcome.Error   -> applyError = outcome.message
                            }
                        }
                    },
                )
            }

            // Preview area — ExoPlayer.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (sourceUri != Uri.EMPTY) {
                    TelegramVideoPlayer(
                        sourceUri = sourceUri,
                        isPlaying = state.isPlaying,
                        trimStartMs = state.trimStartMs.toLong(),
                        trimEndMs = state.trimEndMs.toLong().coerceAtLeast(state.trimStartMs.toLong() + 100),
                        isMuted = false,
                        onCurrentPositionUpdate = { state.currentPlaybackMs = it },
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        seekToMs = scrubRequestMs,
                    )
                }
            }

            // Trim controls — pinned to the bottom, no tab bar.
            Box(
                modifier = Modifier.fillMaxWidth().background(TelegramSecondary)
                    .padding(vertical = 8.dp),
            ) {
                TrimControls(
                    state = state,
                    onScrub = { newMs ->
                        state.currentPlaybackMs = newMs
                        scrubRequestMs = newMs
                    },
                )
            }
        }

        // Export progress overlay — drawn on top of the editor while encoding so users see
        // immediate feedback.
        if (state.isExporting) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = false, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        text = "Exporting… ${(state.exportProgress * 100).toInt()}%",
                        style = TextStyle(color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.exportProgress },
                        color = TelegramAccent,
                        trackColor = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                }
            }
        }

        applyError?.let { msg ->
            VideoErrorDialog(message = msg, onDismiss = { applyError = null })
        }
        if (sourceLoadFailed) {
            VideoErrorDialog(
                message = "Could not open this video. Try a different file.",
                onDismiss = onCancel,
            )
        }
    }
}

@Composable
private fun VideoErrorDialog(message: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                BasicText(
                    text = "OK",
                    style = TextStyle(color = TelegramAccent, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium),
                )
            }
        },
        title = { BasicText(text = "Video editor",
            style = TextStyle(color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)) },
        text = { BasicText(text = message,
            style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)) },
        containerColor = TelegramSecondary,
    )
}

@Composable
private fun TrimControls(
    state: VideoEditorProState,
    onScrub: (Long) -> Unit,
) {
    val durationMs = state.metadata.durationMs
    val isMetadataReady = durationMs > 0
    val playProgress: Float? = if (isMetadataReady) {
        (state.currentPlaybackMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else null
    val areThumbsLoading = state.thumbnails.size < THUMB_COUNT

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            BasicText(
                text = if (isMetadataReady) formatMs(state.trimStartMs.toLong()) else "—:—.—",
                style = TextStyle(color = TelegramAccent, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = if (isMetadataReady) formatMs(state.trimEndMs.toLong()) else "—:—.—",
                style = TextStyle(color = TelegramAccent, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            TelegramTimelineThumbnails(
                frames = state.thumbnails,
                modifier = Modifier.fillMaxSize(),
                height = 56.dp,
                expectedCount = THUMB_COUNT,
            )
            if (isMetadataReady) {
                TelegramTrimHandles(
                    startFraction = state.trimStart,
                    endFraction = state.trimEnd,
                    onTrimChange = { s, e ->
                        state.trimStart = s
                        state.trimEnd = e
                    },
                    playProgress = playProgress,
                    onPlayProgressChange = { fraction ->
                        onScrub((fraction * durationMs).toLong())
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = TelegramAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        BasicText(
                            text = "Loading video…",
                            style = TextStyle(color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val statusText = when {
            !isMetadataReady -> "Loading…"
            areThumbsLoading -> "Loading frames…"
            else -> "Selected: ${formatMs((state.trimEndMs - state.trimStartMs).toLong())}"
        }
        BasicText(
            text = statusText,
            style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
        )
    }
}

@Composable
private fun TopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon, contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalDs = (ms / 100).coerceAtLeast(0)
    val tenths = totalDs % 10
    val sec = (totalDs / 10) % 60
    val min = totalDs / 10 / 60
    return "%d:%02d.%d".format(min, sec, tenths)
}

private sealed class VideoApplyOutcome {
    data class Success(val uri: Uri) : VideoApplyOutcome()
    data class Error(val message: String) : VideoApplyOutcome()
}

/**
 * Load metadata + N evenly-spaced thumbnails using MediaMetadataRetriever. Works directly
 * against the content URI (no full-file copy) and leverages hardware decoders, so a 4K
 * file that took 5+ seconds via NativeVideoDecoder loads in well under a second here.
 *
 * Thumbnails are appended one at a time so the UI can pop them into the strip as they
 * arrive (left-to-right fill, Telegram-style).
 */
private fun loadMetadataAndThumbnails(
    context: Context,
    uri: Uri,
    count: Int,
    onMetadata: (VideoMetadata) -> Unit,
    onThumbnail: (Bitmap) -> Unit,
): Boolean {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(context, uri)
        val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        // FPS is captured in different keys depending on file; fall back to 30 if missing.
        val fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull() ?: 30f

        onMetadata(VideoMetadata(width, height, durationMs, fps, rotation, hasAudio))

        if (durationMs <= 0 || width <= 0 || height <= 0) return true

        val cellMs = durationMs / count
        for (i in 0 until count) {
            val timeUs = (cellMs * i + cellMs / 2) * 1000L
            val bm = mmr.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMB_WIDTH, THUMB_HEIGHT,
            )
            if (bm != null) onThumbnail(bm)
        }
        true
    } catch (t: Throwable) {
        Timber.e(t, "MediaMetadataRetriever failed for %s", uri)
        false
    } finally {
        try { mmr.release() } catch (_: Throwable) {}
    }
}

/**
 * Trim by stream-copy: open the source through MediaExtractor, pick up every sample whose
 * presentation timestamp falls inside [trimStartMs, trimEndMs], and re-mux into a new MP4
 * via MediaMuxer. No decode, no encode — bounded by disk I/O.
 *
 * Caveat (Telegram has the same one for fast-trim): we seek to the nearest sync sample at
 * or before trimStart, so the actual start may be 0.5–2 seconds earlier than requested
 * depending on GOP size. This is what users perceive as "instant trim".
 */
/**
 * Best-effort pre-copy of a content:// source to internal cache. Returns the cached file
 * on success, null on any failure (caller falls back to opening the URI directly). Safe
 * to cancel from the outside — the partially-written file is left for DisposableEffect
 * to delete.
 */
private suspend fun preCopyContentToCache(
    context: Context,
    uri: Uri,
): File? = withContext(Dispatchers.IO) {
    val cacheFile = File(context.cacheDir, "videoedit-source-${System.currentTimeMillis()}")
    try {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        pfd.use { fd ->
            val srcLen = if (fd.statSize > 0) fd.statSize else Long.MAX_VALUE
            FileInputStream(fd.fileDescriptor).use { fis ->
                FileOutputStream(cacheFile).use { fos ->
                    fis.channel.transferTo(0, srcLen, fos.channel)
                }
            }
        }
        if (cacheFile.length() > 0) cacheFile else null.also { cacheFile.delete() }
    } catch (t: Throwable) {
        Timber.w(t, "preCopyContentToCache failed for %s", uri)
        runCatching { cacheFile.delete() }
        null
    }
}

/**
 * Trim export via Telegram's ported MP4Builder. The HEAVY LIFTING lives in
 * `Mp4TrimEngine.java` — a verbatim Java port of Telegram's
 * `MediaCodecVideoConvertor#readAndWriteTracks` loop. This Kotlin wrapper just
 * picks the input path (local cache when pre-copy is ready, else FUSE URI through
 * a ParcelFileDescriptor's path) and forwards progress to the UI.
 *
 * Returns `VideoApplyOutcome.Error` on any failure — the caller decides whether
 * to retry on the slower Transformer path.
 */
@MediaOptIn(UnstableApi::class)
private suspend fun runMp4BuilderTrim(
    context: Context,
    sourceUri: Uri,
    localSource: File?,
    trimStartMs: Long,
    trimEndMs: Long,
    onProgress: (Float) -> Unit,
): VideoApplyOutcome = withContext(Dispatchers.IO) {
    val wallStart = SystemClock.elapsedRealtime()
    val outFile = File(context.cacheDir, "videoedit-out-${System.currentTimeMillis()}.mp4")
    val usingLocalCopy = localSource != null && localSource.exists() && localSource.length() > 0

    // Resolve a real filesystem path. PhotoPicker URIs (content://media/picker/...) are
    // FUSE-backed → every read() syscall goes through user-space FUSE driver → slow on
    // emulators. Telegram avoids this entirely by querying MediaStore's deprecated _data
    // column for a direct filesystem path like /storage/emulated/0/DCIM/Camera/xxx.mp4.
    // We do the same: try _data first, then fall back to pre-copy / PFD.
    var pfd: ParcelFileDescriptor? = null
    val inputPath: String = when {
        usingLocalCopy -> localSource.absolutePath
        sourceUri.scheme == "content" -> {
            val directPath = resolveContentUriToFilePath(context, sourceUri)
            if (directPath != null) {
                Timber.d("runMp4BuilderTrim: resolved content URI to direct path %s", directPath)
                directPath
            } else {
                // Fallback: open a PFD and pass /proc/self/fd/N. FUSE still applies but at
                // least we don't depend on the deprecated _data column being readable.
                pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    ?: return@withContext VideoApplyOutcome.Error("Could not open source.")
                "/proc/self/fd/${pfd.fd}"
            }
        }
        else -> sourceUri.path ?: return@withContext VideoApplyOutcome.Error("Bad source path.")
    }

    // Read source video dimensions for the Mp4Movie size header. The engine itself
    // doesn't strictly need this, but writing 0×0 there confuses some downstream
    // players. Cheap MMR call.
    val (width, height) = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(inputPath)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        mmr.release()
        w to h
    } catch (_: Throwable) {
        0 to 0
    }
    Timber.d("runMp4BuilderTrim: input=%s %dx%d trim=[%d,%d]ms local=%s",
        inputPath, width, height, trimStartMs, trimEndMs, usingLocalCopy)

    // Throttle progress callback — Compose recomposition is expensive.
    var lastProgressMs = 0L
    fun throttledProgress(fraction: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressMs >= 100L) {
            onProgress(fraction.coerceIn(0f, 1f))
            lastProgressMs = now
        }
    }

    try {
        // C++ FFmpeg stream-copy path. If libvideoedit.so isn't loadable on this
        // ABI (currently arm64 is unsupported), fall through to the Java path.
        if (TRIM_IMPL == TrimImpl.CPP_FFMPEG) {
            val nativeResult = try {
                NativeStreamCopyTrim().trim(
                    inputPath = inputPath,
                    outputPath = outFile.absolutePath,
                    startUs = trimStartMs * 1000L,
                    endUs = trimEndMs * 1000L,
                    progress = NativeStreamCopyTrim.ProgressListener { f -> throttledProgress(f) },
                )
            } catch (t: UnsatisfiedLinkError) {
                Timber.w(t, "runMp4BuilderTrim: native lib unavailable, falling back to Java path")
                null
            } catch (t: Throwable) {
                Timber.e(t, "runMp4BuilderTrim: native trim threw")
                null
            }
            if (nativeResult != null) {
                val elapsed = SystemClock.elapsedRealtime() - wallStart
                Timber.d("runMp4BuilderTrim[cpp]: success=%b packets=%d bytes=%d firstPts=%dms lastPts=%dms in %dms err=%s",
                    nativeResult.success, nativeResult.packetsWritten, nativeResult.bytesWritten,
                    nativeResult.firstPtsUs / 1000, nativeResult.lastPtsUs / 1000,
                    elapsed, nativeResult.error)
                if (!nativeResult.success || nativeResult.bytesWritten <= 0) {
                    runCatching { outFile.delete() }
                    return@withContext VideoApplyOutcome.Error("streamcopy-cpp: ${nativeResult.error.ifEmpty { "unknown" }}")
                }
                onProgress(1f)
                return@withContext VideoApplyOutcome.Success(Uri.fromFile(outFile))
            }
            // nativeResult == null → fall through to Java path
        }

        // Java path — Telegram's Mp4TrimEngine port.
        val cb = object : Mp4TrimEngine.ProgressCallback {
            override fun didWriteData(availableSize: Long, progress: Float) {
                throttledProgress(progress)
            }
        }
        val result = Mp4TrimEngine.trim(
            inputPath,
            outFile,
            trimStartMs * 1000L,
            trimEndMs * 1000L,
            true,
            width.coerceAtLeast(2),
            height.coerceAtLeast(2),
            cb,
        )
        val elapsed = SystemClock.elapsedRealtime() - wallStart
        Timber.d("runMp4BuilderTrim[java]: done success=%b bytes=%d in %dms err=%s",
            result.success, result.bytesWritten, elapsed, result.error)
        if (!result.success || result.bytesWritten <= 0) {
            runCatching { outFile.delete() }
            return@withContext VideoApplyOutcome.Error("mp4builder: ${result.error ?: "unknown"}")
        }
        onProgress(1f)
        VideoApplyOutcome.Success(Uri.fromFile(outFile))
    } finally {
        runCatching { pfd?.close() }
    }
}


/**
 * Trim export via Media3's `Transformer` with `experimentalSetTrimOptimizationEnabled`.
 *
 *  - When the requested trim start aligns to a keyframe, Transformer falls into pure
 *    transmux mode (stream-copy, no decode/encode) — same speed regime as a custom
 *    MP4Builder.
 *  - When the start sits mid-GOP, Transformer re-encodes only the leading GOP and stream-
 *    copies the rest, so trim is sample-accurate without paying for full transcode.
 *
 * Works on every ABI because Transformer drives Android's MediaCodec / MediaMuxer under
 * the hood — no native FFmpeg required (which the project currently ships only for
 * x86_64 + armeabi-v7a).
 *
 * Transformer's `start` and `getProgress` must be invoked on a Looper-backed thread; we
 * use the main dispatcher (mirrors the pattern in `mediaupload`'s VideoCompressor).
 */
@MediaOptIn(UnstableApi::class)
private suspend fun runStreamCopyTrim(
    context: Context,
    sourceUri: Uri,
    localSource: File?,
    trimStartMs: Long,
    trimEndMs: Long,
    onProgress: (Float) -> Unit,
): VideoApplyOutcome = withContext(Dispatchers.Main) {
    val wallStart = SystemClock.elapsedRealtime()
    val outFile = File(context.cacheDir, "videoedit-out-${System.currentTimeMillis()}.mp4")
    val usingLocalCopy = localSource != null && localSource.exists() && localSource.length() > 0
    val inputUri: Uri = if (usingLocalCopy) localSource.toUri() else sourceUri
    Timber.d("runStreamCopyTrim (Transformer): %s [%d, %d] → %s (local=%s)",
        inputUri, trimStartMs, trimEndMs, outFile, usingLocalCopy)

    val mediaItem = MediaItem.Builder()
        .setUri(inputUri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trimStartMs)
                .setEndPositionMs(trimEndMs)
                .build()
        )
        .build()
    val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

    val resultDeferred = CompletableDeferred<VideoApplyOutcome>()
    val transformer = Transformer.Builder(context)
        .experimentalSetTrimOptimizationEnabled(true)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                val elapsed = SystemClock.elapsedRealtime() - wallStart
                Timber.d("Transformer onCompleted in %d ms: %d bytes, optimizationResult=%s",
                    elapsed, outFile.length(), exportResult.optimizationResult)
                resultDeferred.complete(VideoApplyOutcome.Success(Uri.fromFile(outFile)))
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                Timber.e(exportException, "Transformer onError")
                runCatching { outFile.delete() }
                resultDeferred.complete(
                    VideoApplyOutcome.Error("Export failed: ${exportException.message ?: exportException.errorCodeName}")
                )
            }
        })
        .build()

    // Progress polling — Transformer reports 0..100 ints, we normalise to 0..1. 10 Hz
    // keeps Compose recomposition off the hot path while still feeling responsive.
    val progressJob = launch(Dispatchers.Main) {
        val holder = ProgressHolder()
        while (isActive && !resultDeferred.isCompleted) {
            val state = transformer.getProgress(holder)
            if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                onProgress((holder.progress / 100f).coerceIn(0f, 1f))
            }
            delay(100)
        }
    }

    try {
        transformer.start(editedMediaItem, outFile.absolutePath)
        val outcome = resultDeferred.await()
        onProgress(1f)
        outcome
    } finally {
        progressJob.cancel()
    }
}

/**
 * Resolve a content:// URI to a direct filesystem path via MediaStore's deprecated but
 * still-functional `_data` column. Same trick Telegram uses in
 * `MediaController.loadGalleryPhotosAlbums` to avoid FUSE overhead on emulators and
 * older devices — `MediaExtractor.setDataSource("/storage/emulated/0/...")` reads
 * through the kernel directly, not through the FUSE user-space layer that PhotoPicker
 * URIs require.
 *
 * Returns null if:
 *  - the URI isn't backed by MediaStore (e.g. PhotoPicker sandboxed URIs since Android 13)
 *  - the `_data` column is missing or unreadable (newer scoped storage rules)
 *  - the resolved file doesn't actually exist or is unreadable (permission denied etc.)
 *
 * Caller falls back to the slower FUSE/PFD path when this returns null.
 */
private fun resolveContentUriToFilePath(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DATA),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                if (idx >= 0) {
                    val path = cursor.getString(idx)
                    if (!path.isNullOrEmpty() && File(path).canRead()) path else null
                } else null
            } else null
        }
    } catch (t: Throwable) {
        Timber.d("resolveContentUriToFilePath: query failed for %s (%s)", uri, t.message)
        null
    }
}
