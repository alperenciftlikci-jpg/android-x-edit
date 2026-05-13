/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.matrix.api.spoiler

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local stash mapping `filename → wasLastSentAsSpoiler`.
 *
 * Why this exists: matrix-rust-sdk-android 26.04.21 has no API surface for MSC4193's
 * `m.spoiler` field on `m.image` / `m.video` content. We can't attach the bit to the
 * outgoing event, and we can't read it from the incoming `ImageMessageContent` either.
 * The full cross-client experience needs an SDK upgrade.
 *
 * For the **sender's own local echo** — the bubble that appears in the sender's
 * timeline immediately after they hit send — we already know the spoiler intent (the
 * user toggled it in the editor / pre-send screen). So we stash it process-locally,
 * keyed by the upload's filename, and look it up when the local echo arrives via
 * `EventMessageMapper`.
 *
 * **Key design choice — Map, not Set, with overwrite-on-mark semantics:**
 * an earlier Set-based version had a stale-state bug. If a user sent
 * `image_1.jpg` with spoiler, then sent the same filename WITHOUT spoiler, the
 * Set still contained `image_1.jpg` and the second echo rendered as spoilered.
 * Using a `Map<String, Boolean>` and always calling `mark(filename, isSpoiler)`
 * on every send (true OR false) keeps the stash in sync with the latest send.
 *
 * Trade-offs (intentional):
 *   • In-memory only — clearing the process forgets every stashed spoiler.
 *     Acceptable because the local echo races to render within seconds of the send
 *     call, so as long as the process is alive at echo time the bit is still here.
 *   • Filename-keyed — collisions are theoretically possible across users sharing
 *     identical filenames within a session, but the upload helper renames to the
 *     source URI's leaf name and the Map's overwrite-on-mark handles legitimate
 *     reuse of the same name.
 *   • Does NOT carry the bit cross-client. Other devices and other Matrix clients
 *     see a plain image because the SDK didn't write `m.spoiler` to the wire. The
 *     TODO at `RustTimeline.sendImage` marks the single point that needs SDK
 *     wiring to fix.
 */
object LocalSpoilerStore {
    private val flagByFilename = ConcurrentHashMap<String, Boolean>()

    /** Record the spoiler intent for `filename`. Call on EVERY send — passing the
     *  literal `isSpoiler` value the user toggled, including `false`. That way two
     *  consecutive sends of the same filename with different spoiler states keep the
     *  stash honest. The previous Set-based API silently let the first send's bit
     *  leak into the second. */
    fun mark(filename: String, isSpoiler: Boolean) {
        flagByFilename[filename] = isSpoiler
    }

    /** Look up the stashed spoiler bit for a filename. Returns `false` if we never
     *  saw this filename in this process — both "we didn't send it" and "the bit
     *  was explicitly false at last send" collapse to the same answer, which is the
     *  correct default for incoming echoes. */
    fun isSpoiler(filename: String): Boolean = flagByFilename[filename] == true

    /** Forget every entry. Tests + UI debug toggles call this; nothing in production
     *  needs it (the map stays bounded by distinct filenames per process lifetime). */
    fun clear() {
        flagByFilename.clear()
    }
}
