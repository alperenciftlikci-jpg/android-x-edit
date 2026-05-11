/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import android.app.Application
import android.util.Log
import com.tencent.matrix.Matrix
import com.tencent.matrix.iocanary.IOCanaryPlugin
import com.tencent.matrix.iocanary.config.IOConfig
import com.tencent.matrix.plugin.DefaultPluginListener
import com.tencent.matrix.report.Issue
import com.tencent.matrix.resource.ResourcePlugin
import com.tencent.matrix.resource.config.ResourceConfig
import com.tencent.matrix.trace.TracePlugin
import com.tencent.matrix.trace.config.TraceConfig
import com.tencent.mrs.plugin.IDynamicConfig

/**
 * Debug-variant bridge to Tencent Matrix APM. Issues detected by Matrix (Activity leaks, I/O
 * problems, ANR, frame drops, slow startup) are forwarded to [PerfRegistry.recordMatrixIssue]
 * so they appear on the perf dashboard alongside our own [trace] sections.
 *
 * Matrix's "EvilMethod" trace (per-method bytecode instrumentation) is intentionally left off —
 * it requires the `matrix-gradle-plugin` to inject timing into every method, which is invasive
 * and slows debug builds. The other features (ANR detection, FPS, leak detection, IO issues)
 * work runtime-only.
 *
 * Initialization is wrapped in a [Throwable] catch: if Matrix fails (native lib mismatch, ABI
 * issue, etc.) the rest of the perf stack still works.
 */
object MatrixBridge {
    private const val TAG = "MatrixBridge"

    @Volatile
    private var started = false

    fun start(application: Application) {
        if (started) return
        try {
            val dynamicConfig = NoopDynamicConfig

            val traceConfig = TraceConfig.Builder()
                .dynamicConfig(dynamicConfig)
                .enableFPS(true)
                .enableEvilMethodTrace(false) // needs the matrix-gradle-plugin
                .enableAnrTrace(true)
                .enableStartup(true)
                .build()

            val resourceConfig = ResourceConfig.Builder()
                .dynamicConfig(dynamicConfig)
                .setDetectDebuger(true)
                .build()

            val ioConfig = IOConfig.Builder()
                .dynamicConfig(dynamicConfig)
                .build()

            ResourcePlugin.activityLeakFixer(application)

            val builder = Matrix.Builder(application)
                .pluginListener(BridgeListener(application))
                .plugin(TracePlugin(traceConfig))
                .plugin(ResourcePlugin(resourceConfig))
                .plugin(IOCanaryPlugin(ioConfig))

            Matrix.init(builder.build())

            // Some plugins need explicit start to begin tracking.
            Matrix.with().getPluginByClass(TracePlugin::class.java)?.start()
            Matrix.with().getPluginByClass(IOCanaryPlugin::class.java)?.start()

            started = true
            Log.i(TAG, "Matrix initialised — TraceCanary + ResourceCanary + IOCanary active")
        } catch (t: Throwable) {
            Log.e(TAG, "Matrix init failed (continuing without Matrix)", t)
        }
    }

    private class BridgeListener(application: Application) : DefaultPluginListener(application) {
        override fun onReportIssue(issue: Issue) {
            super.onReportIssue(issue)
            try {
                val tag = issue.tag ?: "?"
                val content = issue.content?.toString() ?: ""
                PerfRegistry.recordMatrixIssue(
                    kind = mapTagToKind(tag),
                    title = mapTagToTitle(tag),
                    details = content,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Could not forward Matrix issue", t)
            }
        }
    }

    private object NoopDynamicConfig : IDynamicConfig {
        override fun get(key: String, defStr: String): String = defStr
        override fun get(key: String, defInt: Int): Int = defInt
        override fun get(key: String, defLong: Long): Long = defLong
        override fun get(key: String, defBool: Boolean): Boolean = defBool
        override fun get(key: String, defFloat: Float): Float = defFloat
    }

    private fun mapTagToKind(tag: String): String = when {
        tag.contains("ANR", ignoreCase = true) -> "anr"
        tag.contains("EvilMethod", ignoreCase = true) -> "evil_method"
        tag.contains("Resource", ignoreCase = true) -> "leak"
        tag.contains("IO", ignoreCase = true) -> "io"
        tag.contains("FPS", ignoreCase = true) -> "fps"
        tag.contains("StartUp", ignoreCase = true) -> "startup"
        else -> "other"
    }

    private fun mapTagToTitle(tag: String): String = when (mapTagToKind(tag)) {
        "anr" -> "ANR — uygulama yanıt vermiyor"
        "evil_method" -> "Yavaş method tespit edildi"
        "leak" -> "Activity bellek sızıntısı"
        "io" -> "Yavaş veya kapanmamış I/O"
        "fps" -> "Düşük FPS"
        "startup" -> "Yavaş startup"
        else -> "Matrix: $tag"
    }
}
