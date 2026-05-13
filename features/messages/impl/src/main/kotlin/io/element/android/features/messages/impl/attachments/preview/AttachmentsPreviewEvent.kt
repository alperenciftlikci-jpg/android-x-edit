/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import android.net.Uri

sealed interface AttachmentsPreviewEvent {
    data object SendAttachment : AttachmentsPreviewEvent
    data object CancelAndDismiss : AttachmentsPreviewEvent
    data object CancelAndClearSendState : AttachmentsPreviewEvent

    /** Replaces the in-memory media URI after the user finished editing the image.
     *  The spoiler bit is preserved across this event — it lives on `Attachment.Media`
     *  and is toggled exclusively via [ToggleSpoiler] from the top-bar button. */
    data class ReplaceMediaUri(val uri: Uri) : AttachmentsPreviewEvent

    /** Toggle the spoiler bit on the current media attachment in-place. Driven by the
     *  top-bar button so the user can flip it on/off without re-entering the editor —
     *  matches Telegram's kebab-menu "Hide with Spoiler" affordance on the photo picker. */
    data object ToggleSpoiler : AttachmentsPreviewEvent
}
