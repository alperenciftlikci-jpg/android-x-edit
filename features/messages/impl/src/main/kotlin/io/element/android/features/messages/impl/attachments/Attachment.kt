/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import kotlinx.parcelize.Parcelize

@Immutable
sealed interface Attachment : Parcelable {
    @Parcelize
    data class Media(
        val localMedia: LocalMedia,
        /** True when the user marked this media as a spoiler in the editor. Travels
         *  unchanged through pre-processing into the final send call, where it lands as
         *  MSC4193's `m.spoiler` + unstable-prefix flags on the outgoing m.image event.
         *  Default false so attachments not produced by the editor (e.g. raw gallery
         *  picks) stay non-spoiler. */
        val isSpoiler: Boolean = false,
    ) : Attachment
}
