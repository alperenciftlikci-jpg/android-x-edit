/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.videoeditor.native_.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import io.element.android.libraries.videoeditor.native_.NativeVideoDecoder
import io.element.android.libraries.videoeditor.native_.NativeVideoEditor
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramTimelineThumbnails
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramTrimHandles
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramVideoCropOverlay
import io.element.android.libraries.videoeditor.native_.ui.components.TelegramVideoPlayer
import io.element.android.libraries.videoeditor.native_.ui.state.VideoEditorProState
import io.element.android.libraries.videoeditor.native_.ui.state.rememberVideoEditorProState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TelegramAccent = Color(0xFFE5BB3B)
private val TelegramBlue   = Color(0xFF50A8EB)
private val TelegramBackground = Color(0xFF0F0F0F)
private val TelegramSecondary = Color(0xFF1C1C1E)

/**
 * Telegram-style video editor backed by the native pipeline + ExoPlayer.
 *
 *   ┌─────────────────────────────────┐
 *   │ ✕  Edit Video       ▶/⏸    ✓   │
 *   ├─────────────────────────────────┤
 *   │                                 │
 *   │     [PlayerView]                │  ← ExoPlayer preview, optional crop overlay on top
 *   │                                 │
 *   ├─────────────────────────────────┤
 *   │  active tab's controls          │  ← Trim handles / Crop chips / Quality chips
 *   ├─────────────────────────────────┤
 *   │  ✂ Trim   ⊞ Crop   ⚙ Quality   │  ← tab bar
 *   └─────────────────────────────────┘
 *
 * Trim tab → timeline + handles, drag handle → ExoPlayer seeks + loop range updates.
 * Crop tab → ExoPlayer keeps playing, TelegramVideoCropOverlay sits on top.
 * Quality tab → bitrate chips + mute toggle (mute changes player volume too).
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

    // Source path + decoder for thumbnail extraction. The ExoPlayer plays straight from
    // sourceUri; we don't need a fd-resolved path for it. The native decoder needs a real
    // file system path, so we copy content:// URIs to cache once.
    var sourcePath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceUri) {
        timber.log.Timber.d("VideoEditorProScreen: resolving source uri=%s", sourceUri)
        val path = withContext(Dispatchers.IO) { resolveSourcePath(context, sourceUri) }
        if (path == null) {
            timber.log.Timber.e("VideoEditorProScreen: failed to resolve source path")
            sourceLoadFailed = true
        } else {
            sourcePath = path
        }
    }

    val decoder = remember { NativeVideoDecoder() }
    LaunchedEffect(sourcePath) {
        val path = sourcePath ?: return@LaunchedEffect
        val opened = withContext(Dispatchers.IO) {
            if (decoder.open(path)) {
                state.metadata = decoder.metadata
                state.extractThumbnails(decoder)
                true
            } else {
                false
            }
        }
        if (!opened) {
            timber.log.Timber.e("VideoEditorProScreen: NativeVideoDecoder.open failed for %s", path)
            sourceLoadFailed = true
        }
    }
    androidx.compose.runtime.DisposableEffect(decoder) {
        onDispose { decoder.close() }
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
            // Play/Pause
            TopBarIconButton(
                icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = Color.White,
                onClick = { state.isPlaying = !state.isPlaying },
            )
            Spacer(Modifier.size(4.dp))
            // Apply
            TopBarIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "Apply",
                tint = TelegramAccent,
                enabled = !state.isExporting && sourcePath != null,
                onClick = {
                    if (state.isExporting) return@TopBarIconButton
                    val path = sourcePath
                    if (path == null) {
                        applyError = "Source video not loaded yet."
                        return@TopBarIconButton
                    }
                    state.isExporting = true
                    state.exportProgress = 0f
                    state.isPlaying = false
                    applyError = null
                    scope.launch {
                        val outcome = runApply(
                            context = context,
                            inputPath = path,
                            params = state.toEditParams(),
                            onProgress = { state.exportProgress = it },
                        )
                        state.isExporting = false
                        when (outcome) {
                            is VideoApplyOutcome.Success -> onConfirm(outcome.uri)
                            is VideoApplyOutcome.Error   -> applyError = outcome.message
                        }
                    }
                },
            )
        }

        // Preview area — ExoPlayer + per-tab overlay.
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
                    isMuted = state.muteAudio,
                    onCurrentPositionUpdate = { state.currentPlaybackMs = it },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
            if (state.selectedTab == VideoEditorProState.Tab.Crop) {
                TelegramVideoCropOverlay(
                    rectX = state.cropX, rectY = state.cropY,
                    rectW = state.cropW, rectH = state.cropH,
                    aspectLocked = state.cropAspectLocked,
                    onRectChange = state::setCropRect,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }

        // Per-tab controls.
        Box(
            modifier = Modifier.fillMaxWidth().background(TelegramBackground)
                .padding(vertical = 4.dp),
        ) {
            when (state.selectedTab) {
                VideoEditorProState.Tab.Trim    -> TrimTabContent(state)
                VideoEditorProState.Tab.Crop    -> CropTabContent(state)
                VideoEditorProState.Tab.Filters -> FiltersTabContent(state)
                VideoEditorProState.Tab.Quality -> QualityTabContent(state)
            }
        }

        // Tab bar.
        Box(
            modifier = Modifier.fillMaxWidth().background(TelegramSecondary)
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabButton("Trim",    Icons.Filled.ContentCut, state.selectedTab == VideoEditorProState.Tab.Trim) {
                    state.selectedTab = VideoEditorProState.Tab.Trim
                }
                TabButton("Crop",    Icons.Filled.Crop, state.selectedTab == VideoEditorProState.Tab.Crop) {
                    state.selectedTab = VideoEditorProState.Tab.Crop
                }
                TabButton("Filters", Icons.Filled.Tune, state.selectedTab == VideoEditorProState.Tab.Filters) {
                    state.selectedTab = VideoEditorProState.Tab.Filters
                }
                TabButton("Quality", Icons.Filled.HighQuality, state.selectedTab == VideoEditorProState.Tab.Quality) {
                    state.selectedTab = VideoEditorProState.Tab.Quality
                }
            }
        }

        } // end Column

        // Export progress overlay — drawn on top of the editor while encoding so users see
        // immediate feedback. Previously this was a Column sibling at the bottom of the layout
        // and ate space allocations from the preview, making the layout jump on apply.
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
    } // end outer Box
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
private fun TrimTabContent(state: VideoEditorProState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            BasicText(
                text = formatMs(state.trimStartMs.toLong()),
                style = TextStyle(color = TelegramAccent, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = formatMs(state.trimEndMs.toLong()),
                style = TextStyle(color = TelegramAccent, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            TelegramTimelineThumbnails(
                frames = state.thumbnails,
                modifier = Modifier.fillMaxSize(),
                height = 56.dp,
            )
            TelegramTrimHandles(
                startFraction = state.trimStart,
                endFraction = state.trimEnd,
                onTrimChange = { s, e ->
                    state.trimStart = s
                    state.trimEnd = e
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(4.dp))
        val durMs = (state.trimEndMs - state.trimStartMs).toLong()
        BasicText(
            text = "Selected: ${formatMs(durMs)}",
            style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
        )
    }
}

@Composable
private fun CropTabContent(state: VideoEditorProState) {
    val aspectChips = remember {
        listOf<Pair<String, Float?>>(
            "Free" to null,
            "1:1" to 1f,
            "9:16" to 9f / 16f,
            "16:9" to 16f / 9f,
            "4:3" to 4f / 3f,
            "3:4" to 3f / 4f,
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            aspectChips.forEach { (label, ratio) ->
                AspectChipVideo(
                    label = label,
                    isSelected = state.cropAspectLocked == ratio ||
                                 (state.cropAspectLocked == null && ratio == null),
                    onClick = { state.setCropAspect(ratio) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QualityChip("Reset", false) { state.resetCrop() }
        }
    }
}

@Composable
private fun FiltersTabContent(state: VideoEditorProState) {
    // Five Telegram-style sliders. Each maps to a CPU-side post-decode filter coefficient
    // packed into VideoEditParams.toFloatArray() and read back by SimpleFilters.cpp on
    // every decoded frame. Centre-default values match the C++ identity check
    // (`SimpleFilterParams::isIdentity()`), so an untouched Filters tab adds zero overhead.
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        VideoFilterSlider(
            label = "Exposure",
            value = state.exposure,
            range = -2f..2f,
            default = 0f,
            onChange = { state.exposure = it },
        )
        VideoFilterSlider(
            label = "Brightness",
            value = state.brightness,
            range = -1f..1f,
            default = 0f,
            onChange = { state.brightness = it },
        )
        VideoFilterSlider(
            label = "Contrast",
            value = state.contrast,
            range = 0f..2f,
            default = 1f,
            onChange = { state.contrast = it },
        )
        VideoFilterSlider(
            label = "Saturation",
            value = state.saturation,
            range = 0f..2f,
            default = 1f,
            onChange = { state.saturation = it },
        )
        VideoFilterSlider(
            label = "Warmth",
            value = state.warmth,
            range = -1f..1f,
            default = 0f,
            onChange = { state.warmth = it },
        )
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            QualityChip("Reset filters", false) { state.resetFilters() }
        }
    }
}

@Composable
private fun VideoFilterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    onChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    // Tick exactly at 'default' acts as a soft snap when within ~3% of the range.
    fun snap(raw: Float): Float {
        val snapped = if (abs(raw - default) < span * 0.03f) default else raw
        return snapped.coerceIn(range.start, range.endInclusive)
    }
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = label,
                style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = formatSliderValue(value, default),
                style = TextStyle(color = TelegramAccent, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(28.dp).pointerInput(range, default) {
                fun toValueAt(x: Float): Float {
                    val t = (x / size.width).coerceIn(0f, 1f)
                    return range.start + t * span
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onChange(snap(toValueAt(offset.x))) },
                ) { change, _ -> onChange(snap(toValueAt(change.position.x))) }
            },
        ) {
            val trackHeight = with(density) { 2.dp.toPx() }
            val thumbRadius = with(density) { 6.dp.toPx() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val tNorm = ((value - range.start) / span).coerceIn(0f, 1f)
                val dNorm = ((default - range.start) / span).coerceIn(0f, 1f)
                val thumbX = tNorm * size.width
                val defaultX = dNorm * size.width
                // Track baseline.
                drawRect(
                    color = Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(0f, cy - trackHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                )
                // Filled segment from default → thumb (Telegram-style centre fill).
                val (fillStart, fillEnd) =
                    if (thumbX >= defaultX) defaultX to thumbX else thumbX to defaultX
                drawRect(
                    color = TelegramAccent,
                    topLeft = Offset(fillStart, cy - trackHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(fillEnd - fillStart, trackHeight),
                )
                // Default tick.
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = with(density) { 2.dp.toPx() },
                    center = Offset(defaultX, cy),
                )
                // Thumb.
                drawCircle(color = Color.White, radius = thumbRadius, center = Offset(thumbX, cy))
            }
        }
    }
}

private fun formatSliderValue(v: Float, default: Float): String {
    if (abs(v - default) < 0.001f) return "0"
    val delta = v - default
    val rounded = (delta * 100f).roundToInt() / 100f
    return if (rounded > 0) "+%.2f".format(rounded) else "%.2f".format(rounded)
}

@Composable
private fun QualityTabContent(state: VideoEditorProState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            QualityChip("Low (2 Mbps)",  state.qualityKbps == 2000) { state.qualityKbps = 2000 }
            QualityChip("Med (4 Mbps)",  state.qualityKbps == 4000) { state.qualityKbps = 4000 }
            QualityChip("High (8 Mbps)", state.qualityKbps == 8000) { state.qualityKbps = 8000 }
        }
        Spacer(Modifier.height(8.dp))
        if (state.metadata.hasAudio) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { state.muteAudio = !state.muteAudio },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.muteAudio) Icons.Filled.VolumeMute
                                      else Icons.Filled.VolumeUp,
                        contentDescription = if (state.muteAudio) "Unmute" else "Mute",
                        tint = if (state.muteAudio) Color.White.copy(alpha = 0.5f)
                               else TelegramAccent,
                    )
                }
                Spacer(Modifier.size(8.dp))
                BasicText(
                    text = if (state.muteAudio) "Audio muted in export" else "Audio passthrough",
                    style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
                )
            }
        }
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

@Composable
private fun TabButton(label: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    val tint = if (isSelected) TelegramAccent else Color.White.copy(alpha = 0.7f)
    Column(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = label,
            style = TextStyle(color = tint, fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier.height(2.dp).size(width = 20.dp, height = 2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (isSelected) TelegramAccent else Color.Transparent),
        )
    }
}

@Composable
private fun AspectChipVideo(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) TelegramAccent.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp,
                if (isSelected) TelegramAccent else Color.White.copy(alpha = 0.2f),
                RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (isSelected) TelegramAccent else Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun QualityChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) TelegramBlue else Color.Transparent)
            .border(1.dp,
                if (isSelected) TelegramBlue else Color.White.copy(alpha = 0.25f),
                RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            ),
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

private fun resolveSourcePath(context: android.content.Context, uri: Uri): String? {
    if (uri.scheme == "file" || uri.scheme == null) return uri.path
    return try {
        val outFile = File(context.cacheDir, "videoedit-source-${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
    } catch (t: Throwable) { null }
}

private sealed class VideoApplyOutcome {
    data class Success(val uri: Uri) : VideoApplyOutcome()
    data class Error(val message: String) : VideoApplyOutcome()
}

private suspend fun runApply(
    context: android.content.Context,
    inputPath: String,
    params: io.element.android.libraries.videoeditor.native_.VideoEditParams,
    onProgress: (Float) -> Unit,
): VideoApplyOutcome = withContext(Dispatchers.IO) {
    val outFile = File(context.cacheDir, "videoedit-out-${System.currentTimeMillis()}.mp4")
    timber.log.Timber.d("runApply: encoding %s → %s", inputPath, outFile.absolutePath)
    val ok = try {
        NativeVideoEditor().process(
            inputPath = inputPath,
            outputPath = outFile.absolutePath,
            params = params,
            progress = { fraction -> onProgress(fraction) },
        )
    } catch (t: Throwable) {
        timber.log.Timber.e(t, "runApply: native process threw")
        return@withContext VideoApplyOutcome.Error("Export failed: ${t.message}")
    }
    if (!ok) {
        return@withContext VideoApplyOutcome.Error(
            "Native encoder returned an error. Check logcat for details.")
    }
    if (!outFile.exists() || outFile.length() <= 0) {
        return@withContext VideoApplyOutcome.Error("Output file is empty or missing.")
    }
    timber.log.Timber.d("runApply: wrote %s (%d bytes)", outFile.absolutePath, outFile.length())
    VideoApplyOutcome.Success(Uri.fromFile(outFile))
}
