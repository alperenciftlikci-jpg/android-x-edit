/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import android.text.SpannedString
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.components.ATimelineItemEventRow
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayout
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContentProvider
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.protection.ProtectedView
import io.element.android.features.messages.impl.timeline.protection.coerceRatioWhenHidingContent
import io.element.android.libraries.designsystem.components.blurhash.blurHashBackground
import io.element.android.libraries.imageeditor.native_.ui.components.SpoilerOverlay
import io.element.android.libraries.imageeditor.native_.ui.components.SpoilerCoilTransformation
import io.element.android.libraries.matrix.api.spoiler.LocalSpoilerRevealStore
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.time.isTalkbackActive
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link

@Composable
fun TimelineItemImageView(
    content: TimelineItemImageContent,
    hideMediaContent: Boolean,
    onContentClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onShowContentClick: () -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11yLabel = stringResource(CommonStrings.common_image)
    val description = content.caption?.let { "$a11yLabel: $it" } ?: a11yLabel
    Column(modifier = modifier) {
        val containerModifier = if (content.showCaption) {
            Modifier.clip(RoundedCornerShape(10.dp))
        } else {
            Modifier
        }
        // Original Element X behaviour preserved — the blurhash placeholder gives
        // bubbles a coloured shimmer while the photo is fetching, then the
        // `Modifier.background(Color.White)` below covers it once the bitmap arrives.
        // This is the look that ships on `version1.0.1` and what every non-spoiler
        // image bubble in this app has always rendered.
        TimelineItemAspectRatioBox(
            modifier = containerModifier.blurHashBackground(content.blurhash, alpha = 0.9f),
            aspectRatio = coerceRatioWhenHidingContent(content.aspectRatio, hideMediaContent),
        ) {
            ProtectedView(
                hideContent = hideMediaContent,
                onShowClick = onShowContentClick,
            ) {
                // Persist reveal state via the process-wide store — see
                // LocalSpoilerRevealStore docs for the LazyColumn-recycle rationale.
                val revealKey = remember(content.filename, content.mediaSource) {
                    "${content.filename}|${content.mediaSource.safeUrl}"
                }
                var spoilerRevealed by remember(revealKey, content.isSpoiler) {
                    mutableStateOf(!content.isSpoiler || LocalSpoilerRevealStore.isRevealed(revealKey))
                }
                // Telegram's two-bitmap model literally. The bottom AsyncImage is
                // ALWAYS bound to `content.thumbnailMediaRequestData` — same model,
                // same modifier chain main has, never reloaded, never flashes. The
                // spoiler engine (top AsyncImage + dust overlay) is layered ON TOP
                // when isSpoiler && !revealed. When the user reveals, only the top
                // layer disappears; the bottom photo was already loaded and rendered
                // identically the whole time. Earlier versions of this code swapped
                // the single AsyncImage's model based on spoiler state, which caused
                // Coil to reload between the transformed and raw bitmaps — visible
                // as a 2–3 frame flash on revelation and on initial load while the
                // remember key churned.
                val context = LocalContext.current
                val spoilerRequest = remember(content.thumbnailMediaRequestData, content.isSpoiler) {
                    if (content.isSpoiler) {
                        ImageRequest.Builder(context)
                            .data(content.thumbnailMediaRequestData)
                            .transformations(SpoilerCoilTransformation())
                            .build()
                    } else {
                        null
                    }
                }
                Box {
                    var isLoaded by remember { mutableStateOf(false) }
                    // Bottom: original photo. Identical to main's AsyncImage call —
                    // same modifier (fillMaxWidth + bg + click), same model, same
                    // contentScale. The bubble's layout chain ends here exactly as
                    // it did pre-spoiler, so the photo renders pixel-for-pixel like
                    // version1.0.1.
                    AsyncImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isLoaded) Modifier.background(Color.White) else Modifier)
                            .then(
                                if (spoilerRevealed && !isTalkbackActive() && onContentClick != null) {
                                    Modifier
                                        .combinedClickable(
                                            onClick = onContentClick,
                                            onLongClick = onLongClick,
                                        )
                                        .onKeyboardContextMenuAction(onLongClick)
                                } else {
                                    Modifier
                                }
                            ),
                        model = content.thumbnailMediaRequestData,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        contentDescription = description,
                        onState = { isLoaded = it is AsyncImagePainter.State.Success },
                    )
                    // Top: pre-blurred copy painted over the original. Only present
                    // when the bubble is still spoilered; goes away on reveal,
                    // exposing the unchanged original underneath. Uses
                    // `matchParentSize` so the layout chain from the bottom AsyncImage
                    // is not disturbed (the Box sizes itself from the bottom child).
                    if (spoilerRequest != null && !spoilerRevealed) {
                        AsyncImage(
                            modifier = Modifier.matchParentSize(),
                            model = spoilerRequest,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.Center,
                            contentDescription = null,
                        )
                        SpoilerOverlay(
                            modifier = Modifier.matchParentSize(),
                            revealable = true,
                            onRevealed = {
                                LocalSpoilerRevealStore.mark(revealKey)
                                spoilerRevealed = true
                            },
                        )
                    }
                }
            }
        }

        if (content.showCaption) {
            Spacer(modifier = Modifier.height(8.dp))
            val caption = if (LocalInspectionMode.current) {
                SpannedString(content.caption)
            } else {
                content.formattedCaption ?: SpannedString(content.caption)
            }
            CompositionLocalProvider(
                LocalContentColor provides ElementTheme.colors.textPrimary,
                LocalTextStyle provides ElementTheme.typography.fontBodyLgRegular
            ) {
                val aspectRatio = content.aspectRatio ?: DEFAULT_ASPECT_RATIO
                EditorStyledText(
                    modifier = Modifier
                        .padding(horizontal = 4.dp) // This is (12.dp - 8.dp) contentPadding from CommonLayout
                        .widthIn(min = MIN_HEIGHT_IN_DP.dp * aspectRatio, max = MAX_HEIGHT_IN_DP.dp * aspectRatio),
                    text = caption,
                    style = ElementRichTextEditorStyle.textStyle(),
                    onLinkClickedListener = onLinkClick,
                    onLinkLongClickedListener = onLinkLongClick,
                    releaseOnDetach = false,
                    onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
                )
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewPreview(@PreviewParameter(TimelineItemImageContentProvider::class) content: TimelineItemImageContent) = ElementPreview {
    TimelineItemImageView(
        content = content,
        hideMediaContent = false,
        onShowContentClick = {},
        onContentClick = {},
        onLongClick = {},
        onLinkClick = {},
        onLinkLongClick = {},
        onContentLayoutChange = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemImageViewHideMediaContentPreview() = ElementPreview {
    TimelineItemImageView(
        content = aTimelineItemImageContent(),
        hideMediaContent = true,
        onShowContentClick = {},
        onContentClick = {},
        onLongClick = {},
        onLinkClick = {},
        onLinkLongClick = {},
        onContentLayoutChange = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineImageWithCaptionRowPreview() = ElementPreview {
    Column {
        sequenceOf(false, true).forEach { isMine ->
            ATimelineItemEventRow(
                event = aTimelineItemEvent(
                    isMine = isMine,
                    content = aTimelineItemImageContent(
                        filename = "image.jpg",
                        caption = "A long caption that may wrap into several lines",
                        aspectRatio = 2.5f,
                    ),
                    groupPosition = TimelineItemGroupPosition.Last,
                ),
            )
        }
        ATimelineItemEventRow(
            event = aTimelineItemEvent(
                isMine = false,
                content = aTimelineItemImageContent(
                    filename = "image.jpg",
                    caption = "Image with null aspectRatio",
                    aspectRatio = null,
                ),
                groupPosition = TimelineItemGroupPosition.Last,
            ),
        )
    }
}
