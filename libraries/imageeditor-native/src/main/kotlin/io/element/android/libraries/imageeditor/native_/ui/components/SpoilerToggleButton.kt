/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Telegram-style "send with spoiler" toggle. Lives in `:libraries:imageeditor-native` —
 * even though the chip is shown OUTSIDE the editor (it's painted in the pre-send
 * attachment-preview screen next to the dismiss button, just like Telegram's kebab-menu
 * spoiler entry), the whole spoiler feature stack — particle overlay, blurred-bitmap
 * backdrop, this toggle — is owned by this module so contributors editing the spoiler
 * pipeline find everything in one tree.
 *
 * Visual state: eye-off icon, white when off and the Telegram-blue accent when on. Tap
 * fires `onClick`; the parent owns the boolean (typically on the screen's state holder
 * or the Attachment.Media flag).
 *
 * @param isSpoiler current toggle state — drives the tint colour.
 * @param activeColor colour used when [isSpoiler] is true. Defaults to Telegram's
 *                    accent blue (0xFF31A6FF) so it matches the editor's DONE label
 *                    without callers having to thread a theme through.
 * @param iconSize visible icon size; the surrounding tappable region is iconSize + 8 dp
 *                 padding on each side, matching the rest of the top-bar buttons.
 */
@Composable
fun SpoilerToggleButton(
    isSpoiler: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = TelegramSpoilerAccent,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val tint = if (isSpoiler) activeColor else Color.White
    Icon(
        imageVector = Icons.Filled.VisibilityOff,
        contentDescription = "Spoiler",
        tint = tint,
        modifier = modifier
            .padding(8.dp)
            .clickable(onClick = onClick)
            .size(iconSize),
    )
}

/** Same accent colour `PhotoEditorProScreen` uses for its DONE label — extracted here so
 *  the toggle button + the editor's chrome match without a circular module dependency. */
private val TelegramSpoilerAccent = Color(0xFF31A6FF)
