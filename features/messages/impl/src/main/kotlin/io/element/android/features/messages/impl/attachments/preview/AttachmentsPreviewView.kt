/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.IconButton as Material3IconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.error.sendAttachmentError
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorEvent
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoUploadEstimation
import io.element.android.libraries.core.bool.orFalse
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeImage
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.ProgressDialogType
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import io.element.android.libraries.imageeditor.native_.ui.components.SpoileredImage
import io.element.android.libraries.imageeditor.native_.ui.components.SpoilerToggleButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.dialogs.RetryDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.modifiers.niceClickable
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.ElementPreviewDark
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.imageeditor.ImageEditorScreen
import io.element.android.libraries.imageeditor.last.LastImageEditorScreen
import io.element.android.libraries.imageeditor.modular.ModularImageEditorScreen
import io.element.android.libraries.imageeditor.native_.ui.PhotoEditorProScreen
import io.element.android.libraries.imageeditor.photoeditor.PhotoEditorImageEditorScreen
import io.element.android.libraries.videoeditor.native_.ui.VideoEditorProScreen
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.TextComposer
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.formatter.rememberFileSizeFormatter
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsPreviewView(
    state: AttachmentsPreviewState,
    localMediaRenderer: LocalMediaRenderer,
    modifier: Modifier = Modifier,
) {
    fun postSendAttachment() {
        state.eventSink(AttachmentsPreviewEvent.SendAttachment)
    }

    fun postCancel() {
        state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
    }

    fun postClearSendState() {
        state.eventSink(AttachmentsPreviewEvent.CancelAndClearSendState)
    }

    var isEditingImage by remember { mutableStateOf(false) }
    var isEditingVideo by remember { mutableStateOf(false) }
    // Dev-only A/B/C toggle. Pencil → baseline, "M" → modular (uCrop + Jetpack Ink), "P" →
    // burhanrashid52/PhotoEditor. Each editor records perf traces under its own prefix
    // (`imageeditor.*` / `imageeditor.modular.*` / `imageeditor.photoeditor.*`) so the dashboard
    // at localhost:9999 clusters them alphabetically for direct comparison.
    var editorVariant by remember { mutableStateOf(EditorVariant.Baseline) }

    val media = state.attachment as? Attachment.Media
    val mimeType = media?.localMedia?.info?.mimeType
    val isImage = mimeType?.isMimeTypeImage() == true
    val isVideo = mimeType?.isMimeTypeVideo() == true

    if (isEditingImage && media != null) {
        val onCancelEdit = { isEditingImage = false }
        val onConfirmEdit: (android.net.Uri) -> Unit = { editedUri ->
            state.eventSink(AttachmentsPreviewEvent.ReplaceMediaUri(editedUri))
            isEditingImage = false
        }
        when (editorVariant) {
            EditorVariant.Baseline -> ImageEditorScreen(
                sourceUri = media.localMedia.uri,
                onCancel = onCancelEdit,
                onConfirm = onConfirmEdit,
            )
            EditorVariant.Modular -> ModularImageEditorScreen(
                sourceUri = media.localMedia.uri,
                onCancel = onCancelEdit,
                onConfirm = onConfirmEdit,
            )
            EditorVariant.PhotoEditor -> PhotoEditorImageEditorScreen(
                sourceUri = media.localMedia.uri,
                onCancel = onCancelEdit,
                onConfirm = onConfirmEdit,
            )
            EditorVariant.Last -> LastImageEditorScreen(
                sourceUri = media.localMedia.uri,
                onCancel = onCancelEdit,
                onConfirm = onConfirmEdit,
            )
            EditorVariant.NativeTelegramStyle -> PhotoEditorProScreen(
                sourceUri = media.localMedia.uri,
                onCancel = onCancelEdit,
                // Editor no longer owns the spoiler bit — it produces a plain edited URI
                // and the AttachmentsPreviewView's top-bar SpoilerToggleButton handles
                // the flag separately. `ReplaceMediaUri` preserves the existing
                // attachment's isSpoiler (defaults false; presenter merges it explicitly).
                onConfirm = onConfirmEdit,
            )
        }
        return
    }

    if (isEditingVideo && media != null) {
        VideoEditorProScreen(
            sourceUri = media.localMedia.uri,
            onCancel = { isEditingVideo = false },
            onConfirm = { editedUri ->
                state.eventSink(AttachmentsPreviewEvent.ReplaceMediaUri(editedUri))
                isEditingVideo = false
            },
        )
        return
    }

    BackHandler(enabled = state.sendActionState !is SendActionState.Sending.Uploading && state.sendActionState !is SendActionState.Done) {
        postCancel()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BackButton(
                            imageVector = CompoundIcons.Close(),
                            onClick = ::postCancel,
                        )
                        // Telegram-style spoiler toggle right next to the dismiss icon —
                        // matches the kebab-menu placement on Telegram's photo picker.
                        // Only meaningful for images today; we hide for video / file so
                        // the top bar doesn't accumulate inactive controls. The
                        // composable itself ships from :libraries:imageeditor-native
                        // (alongside the SpoilerOverlay it pairs with).
                        if (isImage) {
                            val mediaForSpoiler = state.attachment as? Attachment.Media
                            if (mediaForSpoiler != null) {
                                SpoilerToggleButton(
                                    isSpoiler = mediaForSpoiler.isSpoiler,
                                    onClick = {
                                        state.eventSink(AttachmentsPreviewEvent.ToggleSpoiler)
                                    },
                                )
                            }
                        }
                    }
                },
                title = {},
                actions = {
                    if (isImage) {
                        // Baseline editor (Canvas + Path/Paint, our own implementation).
                        Material3IconButton(onClick = {
                            editorVariant = EditorVariant.Baseline
                            isEditingImage = true
                        }) {
                            Icon(
                                imageVector = CompoundIcons.Edit(),
                                contentDescription = stringResource(CommonStrings.action_edit),
                            )
                        }
                        // Modular editor (uCrop + Jetpack Ink). Dev-only A/B/C toggle.
                        Material3IconButton(onClick = {
                            editorVariant = EditorVariant.Modular
                            isEditingImage = true
                        }) {
                            Text(text = "M")
                        }
                        // PhotoEditor (burhanrashid52/photoeditor — drawing/text/filters/emoji).
                        Material3IconButton(onClick = {
                            editorVariant = EditorVariant.PhotoEditor
                            isEditingImage = true
                        }) {
                            Text(text = "P")
                        }
                        // Last — hybrid: uCrop crop + Jetpack Ink draw + native EditText text.
                        Material3IconButton(onClick = {
                            editorVariant = EditorVariant.Last
                            isEditingImage = true
                        }) {
                            Text(text = "L")
                        }
                        // Native — Telegram-style C++/OpenGL backend with sliders + radial blur.
                        Material3IconButton(onClick = {
                            editorVariant = EditorVariant.NativeTelegramStyle
                            isEditingImage = true
                        }) {
                            Text(text = "N")
                        }
                    } else if (isVideo) {
                        // Trim — Telegram-style native video editor (FFmpeg + AMediaCodec).
                        Material3IconButton(onClick = { isEditingVideo = true }) {
                            Text(text = "✂")
                        }
                    }
                },
            )
        }
    ) { paddingValues ->
        AttachmentPreviewContent(
            modifier = Modifier.padding(paddingValues),
            state = state,
            localMediaRenderer = localMediaRenderer,
            onSendClick = ::postSendAttachment,
        )
    }
    AttachmentSendStateView(
        sendActionState = state.sendActionState,
        onDismissClick = ::postClearSendState,
        onRetryClick = ::postSendAttachment
    )
}

@Composable
private fun AttachmentSendStateView(
    sendActionState: SendActionState,
    onDismissClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    when (sendActionState) {
        is SendActionState.Sending.Processing -> {
            if (sendActionState.displayProgress) {
                ProgressDialog(
                    type = ProgressDialogType.Indeterminate,
                    text = stringResource(CommonStrings.common_preparing),
                    showCancelButton = true,
                    onDismissRequest = onDismissClick,
                )
            }
        }
        is SendActionState.Sending.Uploading -> {
            ProgressDialog(
                type = ProgressDialogType.Indeterminate,
                text = stringResource(id = CommonStrings.common_sending),
                showCancelButton = true,
                onDismissRequest = onDismissClick,
            )
        }
        is SendActionState.Failure -> {
            RetryDialog(
                content = stringResource(sendAttachmentError(sendActionState.error)),
                onDismiss = onDismissClick,
                onRetry = onRetryClick
            )
        }
        else -> Unit
    }
}

@Composable
private fun AttachmentPreviewContent(
    state: AttachmentsPreviewState,
    localMediaRenderer: LocalMediaRenderer,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when (val attachment = state.attachment) {
                is Attachment.Media -> {
                    // Spoiler-on for images: load the bitmap and render via SpoileredImage
                    // — that composable wraps Image + SpoilerOverlay in a Box constrained
                    // to the photo's aspect ratio, so the dust + downscale-blur backdrop
                    // both sit inside the actual image rect (not the surrounding letterbox).
                    // Without this, the overlay would paint the whole preview pane
                    // including the dark space above/below the photo.
                    //
                    // For videos / non-image attachments we fall back to the regular
                    // renderer — videos don't expose a frame bitmap synchronously, so a
                    // proper SpoileredImage variant for them is a separate effort.
                    val context = LocalContext.current
                    val attachmentMimeType = attachment.localMedia.info.mimeType
                    val isImageAttachment = attachmentMimeType.isMimeTypeImage() == true
                    if (attachment.isSpoiler && isImageAttachment) {
                        val previewBitmap = produceState<ImageBitmap?>(initialValue = null, attachment.localMedia.uri) {
                            // Decode on IO; first frame of the screen renders without the
                            // spoiler effect, then snaps to SpoileredImage once the bitmap
                            // arrives. Cheap one-shot decode — same bytes localMediaRenderer
                            // would have loaded anyway.
                            value = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(attachment.localMedia.uri)
                                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                                        ?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        val bm = previewBitmap.value
                        if (bm != null) {
                            SpoileredImage(
                                bitmap = bm,
                                isSpoiler = true,
                                revealable = false,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // Brief pre-decode window — show the renderer as a fallback so
                            // the user isn't staring at a blank pane.
                            localMediaRenderer.Render(attachment.localMedia)
                        }
                    } else {
                        localMediaRenderer.Render(attachment.localMedia)
                    }
                }
            }
        }
        val mimeType = (state.attachment as? Attachment.Media)?.localMedia?.info?.mimeType
        if (mimeType?.isMimeTypeImage() == true) {
            ImageOptimizationSelector(state.mediaOptimizationSelectorState)
        } else if (mimeType?.isMimeTypeVideo() == true) {
            VideoPresetSelector(state = state.mediaOptimizationSelectorState)
        }

        val sizeFormatter = rememberFileSizeFormatter()
        if (state.displayFileTooLargeError) {
            val maxFileUploadSize = state.mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
            if (maxFileUploadSize != null) {
                val content = stringResource(CommonStrings.dialog_file_too_large_to_upload_subtitle, sizeFormatter.format(maxFileUploadSize, true))
                AlertDialog(
                    title = stringResource(CommonStrings.dialog_file_too_large_to_upload_title),
                    content = content,
                    onDismiss = { state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss) },
                )
            }
        }

        AttachmentsPreviewBottomActions(
            state = state,
            onSendClick = onSendClick,
            modifier = Modifier
                .fillMaxWidth()
                .background(ElementTheme.colors.bgCanvasDefault)
                .height(IntrinsicSize.Min)
                .imePadding(),
        )
    }
}

@Composable
private fun ImageOptimizationSelector(state: MediaOptimizationSelectorState) {
    if (state.displayMediaSelectorViews == true) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .niceClickable {
                    state.isImageOptimizationEnabled?.let { value ->
                        state.eventSink(MediaOptimizationSelectorEvent.SelectImageOptimization(!value))
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                text = stringResource(R.string.screen_media_upload_preview_optimize_image_quality_title),
                style = ElementTheme.typography.fontBodyLgRegular,
            )
            Switch(
                modifier = Modifier.height(32.dp),
                checked = state.isImageOptimizationEnabled.orFalse(),
                onCheckedChange = { value -> state.eventSink(MediaOptimizationSelectorEvent.SelectImageOptimization(value)) },
            )
        }
    }
}

@Composable
private fun VideoPresetSelector(
    state: MediaOptimizationSelectorState,
) {
    val videoPresets = state.videoSizeEstimations.dataOrNull()
    var selectedPreset by remember(state.selectedVideoPreset) { mutableStateOf(state.selectedVideoPreset) }

    val displayDialog = state.displayVideoPresetSelectorDialog

    val sizeFormatter = rememberFileSizeFormatter()

    if (state.displayMediaSelectorViews == true && videoPresets != null && state.selectedVideoPreset != null) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .niceClickable { state.eventSink(MediaOptimizationSelectorEvent.OpenVideoPresetSelectorDialog) }
        ) {
            val estimation = videoPresets.find { it.preset == selectedPreset }
            val estimationMb = estimation?.sizeInBytes?.let { sizeFormatter.format(it, true) }
            val title = buildString {
                append(state.selectedVideoPreset.title())
                if (estimationMb != null) {
                    append(" ($estimationMb)")
                }
            }
            Text(text = title, style = ElementTheme.typography.fontBodyLgMedium)
            Text(
                text = stringResource(R.string.screen_media_upload_preview_change_video_quality_prompt),
                style = ElementTheme.typography.fontBodyLgMedium,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }

    if (displayDialog) {
        VideoQualitySelectorDialog(
            selectedPreset = selectedPreset ?: VideoCompressionPreset.STANDARD,
            videoSizeEstimations = videoPresets ?: persistentListOf(),
            maxFileUploadSize = state.maxUploadSize.dataOrNull(),
            onSubmit = { preset ->
                selectedPreset = preset
                state.eventSink(MediaOptimizationSelectorEvent.SelectVideoPreset(preset))
            },
            onDismiss = { state.eventSink(MediaOptimizationSelectorEvent.DismissVideoPresetSelectorDialog) }
        )
    }
}

@Composable
private fun VideoQualitySelectorDialog(
    selectedPreset: VideoCompressionPreset,
    videoSizeEstimations: ImmutableList<VideoUploadEstimation>,
    maxFileUploadSize: Long?,
    onSubmit: (VideoCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeFormatter = rememberFileSizeFormatter()

    var localSelectedPreset by remember(selectedPreset) { mutableStateOf(selectedPreset) }
    val subtitlePartNoFileSize = stringResource(CommonStrings.dialog_video_quality_selector_subtitle_no_file_size)
    val subtitlePartWithFileSize = stringResource(CommonStrings.dialog_video_quality_selector_subtitle_file_size)
    val subtitle = remember(maxFileUploadSize) {
        buildString {
            append(subtitlePartNoFileSize)
            if (maxFileUploadSize != null) {
                append(String.format(subtitlePartWithFileSize, sizeFormatter.format(maxFileUploadSize, true)))
            }
        }
    }
    ListDialog(
        title = stringResource(CommonStrings.dialog_video_quality_selector_title),
        subtitle = subtitle,
        onSubmit = { onSubmit(localSelectedPreset) },
        onDismissRequest = onDismiss,
        applyPaddingToContents = false,
    ) {
        for (videoEstimation in videoSizeEstimations) {
            val preset = videoEstimation.preset
            val isSelected = preset == localSelectedPreset
            item(
                key = preset,
                contentType = preset,
            ) {
                val estimationMb = sizeFormatter.format(videoEstimation.sizeInBytes, true)
                val title = "${preset.title()} ($estimationMb)"
                ListItem(
                    headlineContent = {
                        Text(
                            text = title,
                            style = ElementTheme.typography.fontBodyLgMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = preset.subtitle(),
                            style = ElementTheme.typography.fontBodyMdRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                    },
                    leadingContent = ListItemContent.RadioButton(
                        selected = isSelected,
                    ),
                    onClick = {
                        localSelectedPreset = preset
                    },
                    enabled = videoEstimation.canUpload,
                )
            }
        }
    }
}

@Composable
private fun AttachmentsPreviewBottomActions(
    state: AttachmentsPreviewState,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextComposer(
        modifier = modifier,
        state = state.textEditorState,
        voiceMessageState = VoiceMessageState.Idle,
        composerMode = MessageComposerMode.Attachment,
        onRequestFocus = {},
        onSendMessage = onSendClick,
        showTextFormatting = false,
        onResetComposerMode = {},
        onAddAttachment = {},
        onDismissTextFormatting = {},
        onVoiceRecorderEvent = {},
        onVoicePlayerEvent = {},
        onSendVoiceMessage = {},
        onDeleteVoiceMessage = {},
        onReceiveSuggestion = {},
        resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
        resolveAtRoomMentionDisplay = { TextDisplay.Plain },
        onError = {},
        onTyping = {},
        onSelectRichContent = {},
    )
}

// Only preview in dark, dark theme is forced on the Node.
@Preview
@Composable
internal fun AttachmentsPreviewViewPreview(@PreviewParameter(AttachmentsPreviewStateProvider::class) state: AttachmentsPreviewState) = ElementPreviewDark {
    AttachmentsPreviewView(
        state = state,
        localMediaRenderer = object : LocalMediaRenderer {
            @Composable
            override fun Render(localMedia: LocalMedia) {
                Image(
                    painter = painterResource(id = CommonDrawables.sample_background),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                )
            }
        }
    )
}

@PreviewsDayNight
@Composable
internal fun VideoQualitySelectorDialogPreview() {
    ElementPreview {
        VideoQualitySelectorDialog(
            selectedPreset = VideoCompressionPreset.STANDARD,
            videoSizeEstimations = persistentListOf(
                VideoUploadEstimation(VideoCompressionPreset.HIGH, 2_000_000, canUpload = false),
                VideoUploadEstimation(VideoCompressionPreset.STANDARD, 1_000_000, canUpload = true),
                VideoUploadEstimation(VideoCompressionPreset.LOW, 500_000, canUpload = true)
            ),
            maxFileUploadSize = 1_500_000,
            onSubmit = {},
            onDismiss = {},
        )
    }
}

@Composable
fun VideoCompressionPreset.title(): String {
    return stringResource(
        when (this) {
            VideoCompressionPreset.STANDARD -> CommonStrings.common_video_quality_standard
            VideoCompressionPreset.HIGH -> CommonStrings.common_video_quality_high
            VideoCompressionPreset.LOW -> CommonStrings.common_video_quality_low
        }
    )
}

@Composable
fun VideoCompressionPreset.subtitle(): String {
    return stringResource(
        when (this) {
            VideoCompressionPreset.STANDARD -> CommonStrings.common_video_quality_standard_description
            VideoCompressionPreset.HIGH -> CommonStrings.common_video_quality_high_description
            VideoCompressionPreset.LOW -> CommonStrings.common_video_quality_low_description
        }
    )
}

/**
 * Three image-editor implementations available for A/B/C performance comparison. The toolbar
 * surfaces a button per variant; selecting one sets [editorVariant] and opens the matching screen.
 * Trace section names use distinct prefixes (`imageeditor.*`, `imageeditor.modular.*`,
 * `imageeditor.photoeditor.*`) so the perf dashboard clusters them alphabetically.
 */
private enum class EditorVariant { Baseline, Modular, PhotoEditor, Last, NativeTelegramStyle }
