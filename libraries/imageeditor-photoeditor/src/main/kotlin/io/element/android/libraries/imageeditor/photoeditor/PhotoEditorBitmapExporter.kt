/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.photoeditor

import android.content.Context
import android.net.Uri
import io.element.android.libraries.core.perf.traceAsync
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.SaveFileResult
import ja.burhanrashid52.photoeditor.SaveSettings
import java.io.File

/**
 * Wraps `PhotoEditor.saveAsFile(...)` with our [traceAsync] so the whole save pipeline lands on
 * the dashboard as `imageeditor.photoeditor.export`.
 *
 * PhotoEditor handles its own internals (composing the brushed strokes, text views, filters, and
 * the source ImageView into a final bitmap via its `BitmapUtil`). We treat that as a black box
 * here — the trace measures end-to-end save latency. Sub-section breakdown isn't possible without
 * forking the library, but the side-by-side comparison against `imageeditor.export` (baseline) and
 * `imageeditor.modular.export` is still meaningful.
 */
object PhotoEditorBitmapExporter {

    suspend fun export(
        context: Context,
        engine: PhotoEditor,
    ): Result<Uri> = traceAsync("imageeditor.photoeditor.export") {
        runCatching {
            val outFile = newCacheFile(context)
            val settings = SaveSettings.Builder()
                .setClearViewsEnabled(false)
                .setTransparencyEnabled(false)
                .build()
            val result = engine.saveAsFile(outFile.absolutePath, settings)
            if (result is SaveFileResult.Failure) {
                throw result.exception
            }
            Uri.fromFile(outFile)
        }
    }

    private fun newCacheFile(context: Context): File {
        val dir = File(context.cacheDir, "image-editor-photoeditor").apply { mkdirs() }
        return File(dir, "edited_${System.currentTimeMillis()}.jpg")
    }
}
