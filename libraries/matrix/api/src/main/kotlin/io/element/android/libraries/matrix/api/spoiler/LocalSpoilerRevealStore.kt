/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.matrix.api.spoiler

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local stash of "this spoiler bubble has been tapped open this session".
 *
 * Why this exists: chat-timeline bubbles live inside a LazyColumn that recycles items as
 * they scroll off-screen. Per-composable state held in `remember { … }` is destroyed when
 * the item leaves composition and rebuilt fresh when it re-enters, so a `var revealed by
 * remember(content) { mutableStateOf(false) }` re-defaults to `false` on every scroll
 * back-and-forth — the user reveals a spoiler, scrolls a few messages, returns, and the
 * dust is back. Telegram's `ChatMessageCell` keeps a per-message `revealed` boolean on
 * the underlying `MessageObject` (which outlives the cell view) for this exact reason.
 *
 * We mimic the same lifetime with a process-wide [ConcurrentHashMap.newKeySet] keyed on
 * the bubble's stable identity (composed by the caller, typically filename + media source
 * URL). Once marked, every recomposition of the matching bubble starts in the revealed
 * state, so scrolling stays sticky for the lifetime of the app process. App restart
 * clears the set, which mirrors Telegram's "session-only" reveal — recipients don't get a
 * permanent unhide.
 */
object LocalSpoilerRevealStore {
    private val revealedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Mark the bubble identified by `key` as revealed for the rest of this session. */
    fun mark(key: String) {
        revealedKeys.add(key)
    }

    /** Has this bubble been tapped open already? Determines the initial reveal state. */
    fun isRevealed(key: String): Boolean = key in revealedKeys

    /** Forget every reveal. Tests + UI debug toggles call this; nothing in production. */
    fun clear() {
        revealedKeys.clear()
    }
}
