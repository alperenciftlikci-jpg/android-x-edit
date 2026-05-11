/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny HTTP server, debug only, that exposes [PerfRegistry] over `127.0.0.1`.
 *
 * Usage from a laptop:
 * ```
 * adb forward tcp:9999 tcp:9999
 * # then open http://localhost:9999 in a browser
 * ```
 *
 * Endpoints:
 * - `GET  /`            — HTML report with auto-refresh
 * - `GET  /perf.json`   — JSON snapshot (for tooling / future MCP wrapper)
 * - `POST /perf/reset`  — clear all measurements
 *
 * **Security:** binds to loopback only (`127.0.0.1`). Never listens on the device's external IP.
 *
 * No third-party HTTP dependency: this is ~250 lines of raw [ServerSocket] handling that's plenty
 * for a single-developer debug endpoint. Don't reach for it in production code paths.
 */
object PerfHttpServer {
    const val DEFAULT_PORT: Int = 9999
    private const val TAG = "PerfHttpServer"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var assets: AssetManager? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "PerfHttpServer").apply { isDaemon = true }
    }

    fun start(context: Context, port: Int = DEFAULT_PORT) {
        if (!running.compareAndSet(false, true)) return
        assets = context.applicationContext.assets
        executor.execute {
            try {
                val socket = ServerSocket(port, /* backlog = */ 4, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "Listening on http://127.0.0.1:$port")
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break // server stopped
                    }
                    executor.execute { handleClient(client) }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server on port $port", e)
                running.set(false)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // ignore
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Drain headers — we don't need them, but the client may keep the socket open
                // until we acknowledge the request.
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val (method, path) = parseRequestLine(requestLine) ?: run {
                    s.getOutputStream().write(HTTP_400)
                    return
                }
                val response = route(method, path)
                s.getOutputStream().apply {
                    write(response)
                    flush()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Client error: ${e.message}")
            }
        }
    }

    private fun parseRequestLine(line: String): Pair<String, String>? {
        val parts = line.split(' ')
        if (parts.size < 2) return null
        return parts[0].uppercase() to parts[1]
    }

    private fun route(method: String, path: String): ByteArray = when {
        method == "GET" && (path == "/" || path.startsWith("/?") || path == "/index.html") -> htmlResponse(renderHtml())
        method == "GET" && path == "/perf.json" -> jsonResponse(renderJson())
        method == "GET" && path == "/uplot.js" -> assetResponse("perf/uPlot.iife.min.js", "application/javascript")
        method == "GET" && path == "/uplot.css" -> assetResponse("perf/uPlot.min.css", "text/css")
        method == "POST" && path == "/perf/reset" -> {
            PerfRegistry.reset()
            jsonResponse("""{"ok":true}""")
        }
        else -> HTTP_404
    }

    private fun assetResponse(assetPath: String, mime: String): ByteArray {
        val mgr = assets ?: return HTTP_404
        return try {
            val bytes = mgr.open(assetPath).use { it.readBytes() }
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mime; charset=utf-8\r\n")
                append("Cache-Control: max-age=300\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
            headers + bytes
        } catch (e: IOException) {
            Log.w(TAG, "Asset not found: $assetPath", e)
            HTTP_404
        }
    }

    private fun htmlResponse(body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return headers + bodyBytes
    }

    private fun jsonResponse(body: String): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        return headers + bodyBytes
    }

    private fun renderJson(): String {
        val sections = PerfRegistry.snapshot()
        val frames = PerfRegistry.frameSnapshot()
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"sections\":{")
        sections.entries.forEachIndexed { i, (name, s) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(jsonEscape(name)).append("\":{")
            sb.append("\"count\":").append(s.count).append(',')
            sb.append("\"medianNs\":").append(s.medianNs).append(',')
            sb.append("\"p95Ns\":").append(s.p95Ns).append(',')
            sb.append("\"minNs\":").append(s.minNs).append(',')
            sb.append("\"maxNs\":").append(s.maxNs).append(',')
            sb.append("\"totalNs\":").append(s.totalNs).append(',')
            // Keep ms accessors for older / external consumers — these round to whole ms.
            sb.append("\"medianMs\":").append(s.medianMs).append(',')
            sb.append("\"p95Ms\":").append(s.p95Ms).append(',')
            sb.append("\"maxMs\":").append(s.maxMs).append(',')
            // Per-section FPS — frames observed during this op (NaN when no FPS sample yet).
            sb.append("\"fpsCount\":").append(s.fpsCount).append(',')
            sb.append("\"fpsAverage\":").append(if (s.fpsAverage.isNaN()) "null" else "%.2f".format(s.fpsAverage)).append(',')
            sb.append("\"fpsMin\":").append(if (s.fpsMin.isNaN()) "null" else "%.2f".format(s.fpsMin)).append(',')
            sb.append("\"fpsMax\":").append(if (s.fpsMax.isNaN()) "null" else "%.2f".format(s.fpsMax))
            sb.append('}')
        }
        sb.append("},\"frames\":{")
        sb.append("\"total\":").append(frames.totalFrames).append(',')
        sb.append("\"janky\":").append(frames.jankyFrames).append(',')
        sb.append("\"jankPercentage\":").append(String.format("%.2f", frames.jankPercentage)).append(',')
        sb.append("\"maxFrameNs\":").append(frames.maxFrameNs).append(',')
        sb.append("\"maxFrameMs\":").append(frames.maxFrameMs).append(',')
        sb.append("\"freezes\":").append(frames.freezes).append(',')
        sb.append("\"timelineNs\":[")
        frames.timelineNs.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            sb.append(v)
        }
        sb.append("],")
        sb.append("\"timelineTimestampsMs\":[")
        frames.timelineTimestampsMs.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            sb.append(v)
        }
        sb.append("],")
        // Per-frame concurrent op — for each frame in the timeline, find the longest section
        // event that contained the frame's wall-clock timestamp. Useful so the chart can answer
        // "what was I doing when this frame ran?" via tooltip.
        val sectionEvents = PerfRegistry.recentEvents().filterIsInstance<PerfRegistry.Event.Section>()
        sb.append("\"timelineConcurrentOps\":[")
        frames.timelineTimestampsMs.forEachIndexed { i, ts ->
            if (i > 0) sb.append(',')
            val name = findLongestContainingSection(ts, sectionEvents)
            if (name == null) sb.append("null") else sb.append('"').append(jsonEscape(name)).append('"')
        }
        sb.append("],")
        sb.append("\"effectiveFps\":").append(String.format("%.1f", frames.effectiveFps)).append(',')
        sb.append("\"slowFrames\":").append(frames.slowFrames).append(',')
        sb.append("\"frozenFrames\":").append(frames.frozenFrames).append(',')
        sb.append("\"slowFramePct\":").append(String.format("%.2f", frames.slowFramePercentage)).append(',')
        sb.append("\"frozenFramePct\":").append(String.format("%.3f", frames.frozenFramePercentage)).append(',')
        sb.append("\"p50FrameNs\":").append(frames.p50Ns).append(',')
        sb.append("\"p90FrameNs\":").append(frames.p90Ns).append(',')
        sb.append("\"p95FrameNs\":").append(frames.p95Ns).append(',')
        sb.append("\"p99FrameNs\":").append(frames.p99Ns).append(',')
        sb.append("\"recentJank\":[")
        frames.recentJank.forEachIndexed { i, j ->
            if (i > 0) sb.append(',')
            sb.append("{\"durationNs\":").append(j.durationNs)
            sb.append(",\"durationMs\":").append(j.durationMs)
            sb.append(",\"uiState\":")
            if (j.uiState == null) sb.append("null") else sb.append('"').append(jsonEscape(j.uiState)).append('"')
            sb.append('}')
        }
        sb.append("]},\"memory\":[")
        val memSnaps = PerfRegistry.memorySnapshot()
        memSnaps.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"t\":").append(m.timestampMs)
            sb.append(",\"javaUsedMb\":").append(m.javaHeapUsedMb)
            sb.append(",\"javaMaxMb\":").append(m.javaHeapMaxMb)
            sb.append(",\"nativeMb\":").append(m.nativeHeapAllocatedMb)
            sb.append(",\"pssMb\":").append(m.totalPssMb)
            sb.append('}')
        }
        sb.append("],\"fpsLive\":[")
        val fpsHist = PerfRegistry.fpsHistory()
        fpsHist.forEachIndexed { i, sample ->
            if (i > 0) sb.append(',')
            sb.append("{\"t\":").append(sample.timestampMs)
            sb.append(",\"fps\":").append("%.2f".format(sample.fps))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun renderHtml(): String {
        val sections = PerfRegistry.snapshot()
        val frames = PerfRegistry.frameSnapshot()
        val events = PerfRegistry.recentEvents()

        val verdict = computeVerdict(sections, frames)
        val findings = runDiagnostics(sections, frames, events)
        // Sort sections so the worst (by total impact = count × median) comes first inside each
        // category. Then split into render / state / gesture buckets — gestures are user-paced and
        // shouldn't be coloured red just because the user dragged for 2 seconds.
        val sortedSections = sections.entries.sortedByDescending { it.value.count * it.value.medianNs }
        val byCategory = sortedSections.groupBy { categorize(it.key) }
        val renderCards = byCategory[Category.RENDER].orEmpty()
            .joinToString("\n") { (name, s) -> renderSectionCard(name, s, Category.RENDER) }
        val stateCards = byCategory[Category.STATE].orEmpty()
            .joinToString("\n") { (name, s) -> renderSectionCard(name, s, Category.STATE) }
        val gestureCards = byCategory[Category.GESTURE].orEmpty()
            .joinToString("\n") { (name, s) -> renderSectionCard(name, s, Category.GESTURE) }
        val emptyMessage =
            """<div class="empty">Henüz hiç trace kaydı yok. Telefonda bir özelliği (ör. image export) tetikle, sayfayı bekle.</div>"""
        val waterfalls = renderWaterfalls(sections)
        // frameChart is now rendered client-side by uPlot (see <script> at end of body) so the
        // server only emits a placeholder div + the JSON timeline at /perf.json.
        val eventFeed = renderEventFeed(events)
        val findingsHtml = renderFindings(findings)

        return """
            <!doctype html>
            <html lang="tr"><head>
            <meta charset="utf-8">
            <title>Element X · Perf</title>
            <link rel="stylesheet" href="/uplot.css">
            <script src="/uplot.js"></script>
            <style>
              :root {
                --bg: #0f1115; --panel: #161922; --panel2: #1d212c;
                --fg: #e6e8ef; --muted: #8990a3; --line: #262a37;
                --ok: #4ec07a; --warn: #f0b033; --bad: #ef5350;
              }
              * { box-sizing: border-box; }
              body { font: 13px/1.4 ui-monospace, "JetBrains Mono", Menlo, Consolas, monospace; background: var(--bg); color: var(--fg); padding: 20px; max-width: 1200px; margin: 0 auto; }
              h1 { font-size: 18px; margin: 0; }
              h2 { font-size: 13px; color: var(--muted); margin: 24px 0 10px 0; text-transform: uppercase; letter-spacing: 0.06em; font-weight: 500; }
              .hdrline { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
              .meta { color: var(--muted); font-size: 12px; }
              form { display: inline; }
              button { background: var(--panel2); color: var(--fg); border: 1px solid var(--line); padding: 6px 12px; border-radius: 4px; cursor: pointer; font: inherit; }
              button:hover { background: #262a37; }
              .empty { color: #666; padding: 16px; background: var(--panel); border-radius: 6px; }

              /* verdict banner */
              .verdict { padding: 18px 22px; border-radius: 8px; border-left: 4px solid; margin-bottom: 18px; display: flex; gap: 18px; align-items: center; }
              .verdict.ok   { background: rgba(78,192,122,.10); border-color: var(--ok); }
              .verdict.warn { background: rgba(240,176,51,.10); border-color: var(--warn); }
              .verdict.bad  { background: rgba(239,83,80,.10);  border-color: var(--bad); }
              .verdict .badge { font-size: 28px; }
              .verdict .title { font-size: 16px; font-weight: 600; margin-bottom: 4px; }
              .verdict.ok   .title { color: var(--ok); }
              .verdict.warn .title { color: var(--warn); }
              .verdict.bad  .title { color: var(--bad); }
              .verdict .summary { color: var(--muted); font-size: 13px; }

              /* frame chart (uPlot) */
              .chart { background: var(--panel); padding: 16px; border-radius: 8px; margin-bottom: 8px; }
              .chart .uplot { width: 100% !important; }
              .chart .uplot .u-axis { color: var(--muted); }
              .chart .uplot .u-legend { color: var(--muted); font-size: 12px; }
              .chart .uplot .u-legend th { color: var(--fg); font-weight: 500; }
              .legend { display: flex; gap: 14px; color: var(--muted); font-size: 11px; margin-top: 8px; }
              .swatch { display: inline-block; width: 10px; height: 10px; vertical-align: middle; margin-right: 5px; border-radius: 2px; }
              /* uPlot dark theme tweaks */
              .uplot, .u-wrap { background: var(--panel); }
              .u-axis text { fill: var(--muted) !important; }
              .u-grid { stroke: var(--line) !important; }
              /* Hide uPlot's built-in legend — it overlaps text below the chart and we have our
                 own framehover div + value formatters that show the same info. */
              .uplot .u-legend { display: none !important; }

              /* Frame Health vitals row */
              .vitals { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; margin: 4px 0 10px; }
              .vital { background: var(--panel); padding: 12px 14px; border-radius: 8px; border-left: 3px solid var(--line); }
              .vital.ok   { border-left-color: var(--ok); }
              .vital.warn { border-left-color: var(--warn); }
              .vital.bad  { border-left-color: var(--bad); }
              .vital .vlabel { color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; }
              .vital .vbig { font-size: 24px; font-weight: 700; margin-top: 2px; font-variant-numeric: tabular-nums; color: var(--fg); }
              .vital.ok   .vbig { color: var(--ok); }
              .vital.warn .vbig { color: var(--warn); }
              .vital.bad  .vbig { color: var(--bad); }
              .vital .vsub { color: var(--muted); font-size: 11px; margin-top: 2px; }

              .vh3 { font-size: 12px; color: var(--muted); margin: 14px 0 6px; text-transform: uppercase; letter-spacing: 0.04em; font-weight: 500; }
              .percentiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; }
              .pcell { background: var(--panel); padding: 10px 12px; border-radius: 6px; }
              .pcell .plabel { color: var(--muted); font-size: 11px; }
              .pcell .pval { font-size: 18px; font-weight: 600; color: var(--fg); margin-top: 2px; font-variant-numeric: tabular-nums; }

              /* Per-tool FPS table — primary "which op is jank" view */
              .ptable { width: 100%; border-collapse: separate; border-spacing: 0; margin-bottom: 8px; }
              .ptable thead th { background: var(--panel); color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; padding: 8px 10px; text-align: right; font-weight: 500; border-bottom: 1px solid var(--line); }
              .ptable thead th.ptname { text-align: left; }
              .ptable .ptrow td { padding: 8px 10px; border-bottom: 1px solid var(--line); font-size: 12px; text-align: right; font-variant-numeric: tabular-nums; }
              .ptable .ptrow td.ptname { text-align: left; }
              .ptable .ptrow td.ptname code { background: var(--panel2); padding: 2px 6px; border-radius: 3px; }
              .ptable .ptrow td.ptname .ptdesc { color: var(--muted); font-size: 11px; margin-top: 4px; line-height: 1.4; max-width: 380px; }
              .ptable .ptrow td.ptico { text-align: center; width: 28px; font-size: 14px; }
              .ptable .ptrow td.ptverdict { font-size: 11px; color: var(--muted); }
              .ptable .ptrow.ok    td.ptfps b { color: var(--ok); font-size: 14px; }
              .ptable .ptrow.warn  td.ptfps b { color: var(--warn); font-size: 14px; }
              .ptable .ptrow.bad   td.ptfps b { color: var(--bad); font-size: 14px; }
              .ptable .ptrow.bad   { background: rgba(239,83,80,.06); }
              .ptable .ptrow.warn  { background: rgba(240,176,51,.05); }
              .ptable .ptrow td.ptmin, .ptable .ptrow td.ptmax, .ptable .ptrow td.ptcount { color: var(--muted); }
              .ptable .ptrow.neutral td.ptfps b { color: var(--muted); font-size: 13px; font-weight: 500; }
              .ptable .ptrow.neutral { opacity: 0.85; }
              .ptable .ptrow td.ptfps .ptunit { color: var(--muted); font-size: 10px; margin-left: 4px; font-weight: 400; }
              .ptable .ptseparator td { padding: 8px 10px; background: var(--panel2); color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; border-bottom: 1px solid var(--line); }

              /* Library SLA panels */
              .slas { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 10px; }
              .sla { background: var(--panel); border-radius: 8px; padding: 14px 16px; border-left: 3px solid var(--line); }
              .sla.ok      { border-left-color: var(--ok); }
              .sla.warn    { border-left-color: var(--warn); }
              .sla.bad     { border-left-color: var(--bad); }
              .sla.neutral { border-left-color: var(--muted); }
              .slahead { display: flex; gap: 12px; align-items: center; margin-bottom: 10px; }
              .slabadge { font-size: 22px; }
              .slatitle { font-weight: 600; }
              .slatitle .slaverdict { font-size: 11px; padding: 2px 8px; border-radius: 3px; margin-left: 8px; vertical-align: middle; letter-spacing: 0.04em; }
              .sla.ok   .slatitle .slaverdict { background: rgba(78,192,122,.18); color: var(--ok); }
              .sla.warn .slatitle .slaverdict { background: rgba(240,176,51,.18); color: var(--warn); }
              .sla.bad  .slatitle .slaverdict { background: rgba(239,83,80,.20); color: var(--bad); }
              .slasub { color: var(--muted); font-size: 11px; margin-top: 2px; }
              .slasub code { background: var(--panel2); padding: 1px 5px; border-radius: 3px; }
              .slabody { color: var(--muted); font-size: 12px; }
              .slagrid { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 6px; }
              .slacell { background: var(--panel2); padding: 6px 10px; border-radius: 4px; }
              .slacell-label { color: var(--muted); font-size: 10px; text-transform: uppercase; letter-spacing: 0.04em; }
              .slacell-val { font-size: 16px; font-weight: 600; color: var(--fg); font-variant-numeric: tabular-nums; }
              .slacell-val.small { font-size: 12px; font-weight: 400; }
              .slacell-val.vbad { color: var(--bad); }
              .slahint { margin-top: 8px; padding: 8px 10px; background: rgba(239,83,80,.10); border-radius: 4px; color: var(--bad); font-size: 11px; }
              .slahint code { background: var(--panel); padding: 1px 5px; border-radius: 3px; color: var(--fg); }

              /* 3-chart cheat sheet */
              .chartguide { background: var(--panel); border-radius: 8px; padding: 8px 14px; margin-bottom: 8px; }
              .cgrow { display: flex; gap: 14px; padding: 6px 0; align-items: baseline; font-size: 12px; }
              .cgrow:not(:last-child) { border-bottom: 1px solid var(--line); }
              .cgnum { color: var(--fg); font-weight: 600; min-width: 130px; flex-shrink: 0; }
              .cgdesc { color: var(--muted); }
              .cgdesc strong { color: var(--fg); }

              /* category hint paragraphs under each h2 */
              .cathint { color: var(--muted); font-size: 11px; margin: -6px 0 10px 0; line-height: 1.5; }
              .cathint strong { color: var(--fg); }

              /* fps badge inside frame-chart panel */
              .fpsbadge { display: flex; gap: 12px; align-items: baseline; margin-bottom: 8px; color: var(--muted); font-size: 12px; }
              .fpsnum { color: var(--ok); font-size: 22px; font-weight: 700; margin: 0 6px; font-variant-numeric: tabular-nums; }
              .fpsnum.warn { color: var(--warn); }
              .fpsnum.bad { color: var(--bad); }
              .fpsnum.idle { color: var(--muted); font-weight: 500; font-size: 18px; }
              .fpsbadge small { color: var(--muted); font-size: 10px; }

              /* memory stats summary above memory chart */
              .memstats { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 8px; font-size: 12px; }
              .memstats .stat { color: var(--muted); }
              .memstats .stat b { color: var(--fg); font-size: 14px; margin-left: 4px; font-variant-numeric: tabular-nums; }
              .memstats .stat small { color: var(--muted); font-size: 10px; margin-left: 4px; }
              .memstats .stat.ok   b { color: var(--ok);   }
              .memstats .stat.warn b { color: var(--warn); }
              .memstats .stat.bad  b { color: var(--bad);  }

              /* memory verdict banner (mirrors the global verdict but scoped to memory) */
              .memverdict { padding: 12px 16px; border-radius: 8px; border-left: 4px solid; margin: -2px 0 10px 0; display: flex; gap: 14px; align-items: center; }
              .memverdict.ok   { background: rgba(78,192,122,.10); border-color: var(--ok); }
              .memverdict.warn { background: rgba(240,176,51,.10); border-color: var(--warn); }
              .memverdict.bad  { background: rgba(239,83,80,.10);  border-color: var(--bad); }
              .memverdict-badge { font-size: 22px; }
              .memverdict-title { font-weight: 600; margin-bottom: 2px; }
              .memverdict.ok   .memverdict-title { color: var(--ok); }
              .memverdict.warn .memverdict-title { color: var(--warn); }
              .memverdict.bad  .memverdict-title { color: var(--bad); }
              .memverdict-summary { color: var(--muted); font-size: 12px; }

              /* memory threshold legend */
              .memlegend { background: var(--panel); border-radius: 8px; padding: 12px 16px; margin: 8px 0 4px; }
              .memlegend-row { display: flex; gap: 8px; align-items: center; font-size: 11px; padding: 3px 0; flex-wrap: wrap; }
              .memlegend-label { color: var(--muted); width: 130px; flex-shrink: 0; }
              .memlegend-band { padding: 2px 8px; border-radius: 3px; font-size: 11px; }
              .memlegend-band.ok   { background: rgba(78,192,122,.14); color: var(--ok); }
              .memlegend-band.warn { background: rgba(240,176,51,.14); color: var(--warn); }
              .memlegend-band.bad  { background: rgba(239,83,80,.14);  color: var(--bad); }

              /* Hover tooltip below the FPS chart — populated by uPlot's setCursor hook */
              .framehover { color: var(--muted); font-size: 12px; padding: 8px 10px; margin-top: 4px; border-radius: 4px; background: var(--panel2); border-left: 3px solid var(--line); }
              .framehover code { background: var(--panel); padding: 1px 5px; border-radius: 3px; color: var(--fg); }
              .framehover.fhok    { border-left-color: var(--ok);   color: var(--fg); }
              .framehover.fhwarn  { border-left-color: var(--warn); color: var(--fg); }
              .framehover.fhbad   { border-left-color: var(--bad);  color: var(--fg); }

              /* "Şu an ne oluyor" activity feed under the FPS chart */
              .activity { background: var(--panel); border-radius: 8px; padding: 4px 0; max-height: 320px; overflow-y: auto; margin-top: 4px; }
              .actrow { display: grid; grid-template-columns: 78px 1fr 90px 110px; gap: 12px; padding: 8px 16px; border-bottom: 1px solid var(--line); align-items: center; font-size: 12px; }
              .actrow:last-child { border-bottom: 0; }
              .actrow .actts { color: var(--muted); font-variant-numeric: tabular-nums; }
              .actrow .actname { color: var(--fg); word-break: break-all; }
              .actrow .actname code { background: var(--panel2); padding: 1px 4px; border-radius: 2px; }
              .actrow .actdur { text-align: right; color: var(--muted); font-variant-numeric: tabular-nums; }
              .actrow .actfps { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; padding: 2px 6px; border-radius: 3px; }
              .actrow .actfps.muted { background: transparent; color: var(--muted); font-weight: 400; }
              .actrow.ok       .actfps { background: rgba(78,192,122,.14);  color: var(--ok); }
              .actrow.warn     .actfps { background: rgba(240,176,51,.14);  color: var(--warn); }
              .actrow.bad      .actfps { background: rgba(239,83,80,.16);   color: var(--bad); }
              .actrow.neutral  .actfps { color: var(--muted); }
              .actrow .muted { color: var(--muted); font-size: 11px; }

              /* GC drops list */
              .gcdrops { list-style: none; padding: 8px 16px; margin: 0; background: var(--panel); border-radius: 8px; }
              .gcdrops li { padding: 4px 0; color: var(--muted); font-size: 12px; }
              .gcdrops li strong { color: var(--ok); margin-right: 6px; font-variant-numeric: tabular-nums; }

              /* section cards */
              .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
              .card { background: var(--panel); border-radius: 8px; padding: 14px 16px; border: 1px solid var(--line); }
              .card.ok   { border-left: 3px solid var(--ok); }
              .card.warn { border-left: 3px solid var(--warn); }
              .card.bad  { border-left: 3px solid var(--bad); }
              .card .catbadge { display: inline-block; font-size: 10px; padding: 1px 6px; border-radius: 3px; margin-bottom: 6px; letter-spacing: 0.04em; text-transform: uppercase; }
              .card .catbadge.render  { background: rgba(78,192,122,.14); color: var(--ok); }
              .card .catbadge.state   { background: rgba(240,176,51,.14); color: var(--warn); }
              .card .catbadge.gesture { background: rgba(137,144,163,.16); color: var(--muted); }
              .card .name { color: var(--fg); font-size: 13px; margin-bottom: 4px; word-break: break-all; }
              .card .big  { font-size: 26px; font-weight: 600; }
              .card.ok   .big { color: var(--ok); }
              .card.warn .big { color: var(--warn); }
              .card.bad  .big { color: var(--bad); }
              /* Gesture grid is neutral — stroke length is user-paced, not a perf signal. */
              .grid.neutral .card { border-left-color: var(--line) !important; }
              .grid.neutral .card .big { color: var(--fg) !important; }
              .grid.neutral .card.ok   .hist > span,
              .grid.neutral .card.warn .hist > span,
              .grid.neutral .card.bad  .hist > span { background: var(--muted) !important; }
              .card .big small { color: var(--muted); font-size: 12px; font-weight: 400; margin-left: 4px; }
              .card .stats { color: var(--muted); font-size: 11px; margin-top: 4px; }
              .card svg.spark { display: block; width: 100%; height: 28px; margin-top: 8px; }

              /* findings */
              .findings { display: flex; flex-direction: column; gap: 8px; }
              .finding { background: var(--panel); border-radius: 8px; padding: 12px 16px; display: grid; grid-template-columns: 22px 1fr; gap: 12px; align-items: start; border-left: 3px solid var(--line); }
              .finding.bad  { border-left-color: var(--bad); }
              .finding.warn { border-left-color: var(--warn); }
              .finding.info { border-left-color: var(--ok); }
              .finding .ico { font-size: 16px; }
              .finding .ftitle { font-weight: 600; margin-bottom: 2px; }
              .finding.bad  .ftitle { color: var(--bad); }
              .finding.warn .ftitle { color: var(--warn); }
              .finding.info .ftitle { color: var(--ok); }
              .finding .fbody { color: var(--muted); font-size: 12px; }
              .finding .fbody code { color: var(--fg); background: var(--panel2); padding: 1px 5px; border-radius: 3px; font-size: 11px; }

              /* waterfall */
              .waterfall { background: var(--panel); border-radius: 8px; padding: 14px 18px; margin-bottom: 10px; }
              .waterfall .head { display: flex; justify-content: space-between; margin-bottom: 8px; }
              .waterfall .head .ttl { font-weight: 600; }
              .waterfall .head .total { color: var(--muted); font-size: 12px; }
              .waterfall .row { display: grid; grid-template-columns: 200px 1fr 70px 50px; gap: 10px; align-items: center; padding: 3px 0; font-size: 12px; }
              .waterfall .row .lbl { color: var(--muted); }
              .waterfall .row.bad .lbl { color: var(--bad); }
              .waterfall .row.warn .lbl { color: var(--warn); }
              .waterfall .bar { background: var(--panel2); height: 14px; border-radius: 2px; overflow: hidden; }
              .waterfall .bar > span { display: block; height: 100%; }
              .waterfall .row.ok   .bar > span { background: var(--ok); }
              .waterfall .row.warn .bar > span { background: var(--warn); }
              .waterfall .row.bad  .bar > span { background: var(--bad); }
              .waterfall .ms { text-align: right; font-variant-numeric: tabular-nums; }
              .waterfall .pct { text-align: right; color: var(--muted); }

              /* histogram inside cards */
              .card .hist { display: flex; gap: 1px; height: 14px; align-items: end; margin-top: 6px; }
              .card .hist > span { flex: 1; min-height: 1px; }
              .card.ok   .hist > span { background: var(--ok); }
              .card.warn .hist > span { background: var(--warn); }
              .card.bad  .hist > span { background: var(--bad); }
              .card .histlabels { display: flex; justify-content: space-between; color: var(--muted); font-size: 10px; margin-top: 2px; }
              .card .extra { color: var(--muted); font-size: 11px; margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--line); }
              /* Per-section FPS line — frames captured while this op was running */
              .card .fpsrow { font-size: 12px; margin-top: 6px; padding: 6px 8px; border-radius: 4px; display: flex; gap: 8px; align-items: baseline; }
              .card .fpsrow b { font-size: 16px; font-variant-numeric: tabular-nums; }
              .card .fpsrow .fpsdetail { color: var(--muted); font-size: 10px; margin-left: auto; }
              .card .fpsrow.fps-ok   { background: rgba(78,192,122,.10); color: var(--ok); }
              .card .fpsrow.fps-warn { background: rgba(240,176,51,.10); color: var(--warn); }
              .card .fpsrow.fps-bad  { background: rgba(239,83,80,.12);  color: var(--bad); }

              /* event feed */
              .feed { background: var(--panel); border-radius: 8px; padding: 6px 0; max-height: 360px; overflow-y: auto; }
              .feed .row { display: grid; grid-template-columns: 80px 24px 1fr 80px; gap: 12px; padding: 6px 16px; border-bottom: 1px solid var(--line); align-items: center; }
              .feed .row:last-child { border-bottom: 0; }
              .feed .ts { color: var(--muted); font-size: 11px; }
              .feed .icon { font-size: 14px; text-align: center; }
              .feed .label { color: var(--fg); }
              .feed .label .sub { color: var(--muted); font-size: 11px; margin-left: 8px; }
              .feed .ms { text-align: right; font-weight: 600; }
              .feed .ok   .ms,    .feed .ok   .icon { color: var(--ok);   }
              .feed .warn .ms,    .feed .warn .icon { color: var(--warn); }
              .feed .bad  .ms,    .feed .bad  .icon { color: var(--bad);  }
            </style>
            </head><body>
            <div class="hdrline">
              <div>
                <h1>Element X · perf</h1>
                <div class="meta">2 saniyede bir yenilenir · ${sections.size} section · ${frames.totalFrames} frame</div>
              </div>
              <form method="POST" action="/perf/reset"><button type="submit">Sıfırla</button></form>
            </div>

            <div class="verdict ${verdict.cls}">
              <div class="badge">${verdict.badge}</div>
              <div>
                <div class="title">${verdict.title}</div>
                <div class="summary">${verdict.summary}</div>
              </div>
            </div>

            ${if (findings.isEmpty()) "" else """<h2>Otomatik tanı</h2><div class="findings">$findingsHtml</div>"""}

            ${if (waterfalls.isBlank()) "" else """<h2>Waterfall — bileşik operasyonların kırılımı</h2>$waterfalls"""}

            ${renderPerToolFpsHtml(sections)}

            ${renderSlaPanelsHtml(sections)}

            ${renderFrameHealthHtml(frames)}

            <h2>📊 FPS chart kılavuzu — hangisi ne için?</h2>
            <div class="chartguide">
              <div class="cgrow"><span class="cgnum">📡 Canlı FPS</span><span class="cgdesc"><strong>Asıl metrik.</strong> Saniyede gerçekten ekrana basılan frame sayısı. 60Hz cihazda max 60. Aktifken bunu izle, 0 = idle (normal).</span></div>
              <div class="cgrow"><span class="cgnum">🎬 Frame timing</span><span class="cgdesc">Her frame'in CPU verimliliği (1000/ms, cap 120). Bar bar inceleyebilirsin, hover ile o anki operasyonu görürsün.</span></div>
              <div class="cgrow"><span class="cgnum">🚀 Uncapped</span><span class="cgdesc">Aynı veri, cap yok. Çok hızlı frame'ler 1000+ fps olarak görünür → "headroom var" göstergesi.</span></div>
            </div>

            <h2>📡 Canlı FPS — wall-clock (asıl perf metriği)</h2>
            <p class="cathint">
              <strong>"Saniyede ekrana kaç frame basıldı?"</strong> — display refresh rate ile sınırlı (60 Hz cihaz → max 60).
              Boş ekran = düşük FPS (Android sadece animasyon/input olduğunda frame üretir, idle'da normaldir).
              Aktif kullanım sırasında <code>60</code>'a yakın olmalı, <code>30</code> altına düşerse gözle görülen takılma olur.
              Aşağıdaki frame timing chart'ı ise farklı bir şeyi ölçer — açıklama orada.
            </p>
            <div class="chart">
              <div class="fpsbadge">
                <div>Şu anki FPS:
                  <span id="liveFpsValue" class="fpsnum">--</span>
                  <small>son 1 saniyede ölçülen · son 60 saniye chart'ta</small>
                </div>
                <div style="margin-left:auto;color:var(--muted);font-size:11px">
                  ortalama (60s): <b id="liveFpsAvg" style="color:var(--fg)">--</b>
                  · min: <b id="liveFpsMin" style="color:var(--fg)">--</b>
                  · max: <b id="liveFpsMax" style="color:var(--fg)">--</b>
                </div>
              </div>
              <div id="liveFpsChart" style="width:100%;height:160px"></div>
              <div class="legend">
                <span><span class="swatch" style="background:var(--ok)"></span>≥55 fps · target 60</span>
                <span><span class="swatch" style="background:var(--warn)"></span>30–54 fps · jank</span>
                <span><span class="swatch" style="background:var(--bad)"></span>&lt;30 fps · ciddi gecikme</span>
                <span style="margin-left:auto;color:var(--muted)">1s'de bir yeni örnek</span>
              </div>
            </div>

            <h2>🎬 Frame timing — instantaneous (frame verimliliği)</h2>
            <p class="cathint">
              <strong>"Her frame CPU'da ne kadar hızlı bitti?"</strong> — formül <code>1000 / frame_ms</code>, display refresh rate'i bypass eder.
              60 Hz bir cihazda bile 100-120 görebilirsin; bu "frame işi 8 ms'de bitti, 8.6 ms vsync'ı bekledi" anlamına gelir = <strong>headroom var, sağlıklı</strong>.
              Üst Canlı FPS'in <code>60</code> ama buradaki <code>120</code> ise → app'in çok rahat çalıştığını gösterir.
              Üst <code>30</code> ve burası da <code>30</code> ise → asıl perf sorunu var.
              Her bar'a hover ile o anki operasyonu göreceksin.
            </p>
            <div class="chart">
              <div class="fpsbadge">
                <div>Şu anki FPS:
                  <span id="fpsValue" class="fpsnum">${"%.1f".format(frames.effectiveFps)}</span>
                  <small>son ${frames.timelineNs.size} frame ortalaması · 60 hedef · 30 minimum</small>
                </div>
              </div>
              <div id="frameChart" style="width:100%;height:160px"></div>
              <div id="frameHover" class="framehover">Bir bar'ın üstüne gelince hangi operasyonun çalıştığını burada göreceksin.</div>
              <div class="legend">
                <span><span class="swatch" style="background:var(--ok)"></span>≥55 fps (akıcı)</span>
                <span><span class="swatch" style="background:var(--warn)"></span>30–54 fps (jank, hissedilen takılma)</span>
                <span><span class="swatch" style="background:var(--bad)"></span>&lt;30 fps (ciddi gecikme)</span>
                <span style="margin-left:auto;color:var(--muted)">2s'de bir otomatik güncelleniyor</span>
              </div>
            </div>

            <h2>🚀 Frame timing — UNCAPPED (frame'in teorik tavanı)</h2>
            <p class="cathint">
              Aynı frame data, ama <strong>cap yok</strong> — Y ekseni otomatik ölçeklenir. Çok hızlı frame'ler (örn. 1ms'de biten = 1000 fps) burada gerçek değerleriyle görünür.
              60 Hz cihazda bile 500-1000+ fps okumaları "frame işi son derece hızlı tamamlandı" anlamına gelir.
              Display refresh rate sınırı yok, sadece <strong>frame'in CPU verimliliği</strong>.
            </p>
            <div class="chart">
              <div class="fpsbadge">
                <div>Şu an pik: <span id="uncappedFpsMax" class="fpsnum">--</span><small>· son 60 frame içindeki en hızlı frame</small></div>
                <div style="margin-left:auto;color:var(--muted);font-size:11px">
                  ortalama: <b id="uncappedFpsAvg" style="color:var(--fg)">--</b>
                </div>
              </div>
              <div id="uncappedFpsChart" style="width:100%;height:160px"></div>
              <div class="legend">
                <span style="color:var(--muted)">Y ekseni otomatik ölçeklenir · cap yok</span>
                <span style="margin-left:auto;color:var(--muted)">2s'de bir otomatik güncelleniyor</span>
              </div>
            </div>

            ${renderRecentActivityWithFps(events)}

            <h2>💾 Memory — heap + native + PSS</h2>
            ${renderMemoryVerdictHtml()}
            <div class="chart">
              <div class="memstats" id="memStats">
                ${renderMemoryStatsHtml()}
              </div>
              <div id="memChart" style="width:100%;height:160px"></div>
              <div class="legend">
                <span><span class="swatch" style="background:var(--ok)"></span>Java heap used (MB)</span>
                <span><span class="swatch" style="background:var(--warn)"></span>Native heap (MB)</span>
                <span><span class="swatch" style="background:var(--bad)"></span>Total PSS (MB)</span>
              </div>
            </div>
            ${renderMemoryThresholdLegend()}
            ${renderGcDropsHtml()}

            ${if (sections.isEmpty()) "<h2>Operasyonlar</h2>$emptyMessage" else ""}

            ${if (renderCards.isNotEmpty()) """
            <h2>🎨 Render &amp; Export — gerçek perf metriği</h2>
            <p class="cathint">Save / decode / encode / paint maliyeti. Renkler anlamlı: ≤50ms yeşil, 50–200ms sarı, &gt;200ms kırmızı.</p>
            <div class="grid">$renderCards</div>
            """ else ""}

            ${if (stateCards.isNotEmpty()) """
            <h2>⚡ State mutations — anlık işlemler</h2>
            <p class="cathint">Tek satırlık state assignment'ları (renk seç, tool değiştir, undo). Sub-millisecond beklenir; &gt;5ms ise şüpheli.</p>
            <div class="grid">$stateCards</div>
            """ else ""}

            ${if (gestureCards.isNotEmpty()) """
            <h2>👆 Kullanıcı gesture'ları — sadece davranış, perf metriği DEĞİL</h2>
            <p class="cathint">Parmak basılı tutma süresi (stroke, drag, gesture). 2 saniye çizmek 2 saniye sürer — kırmızı renk yanıltıcı olduğu için kapalı. Asıl perf bu süreçteki <strong>frame jank/donma</strong> sayısında (yukarıdaki frame chart + olay akışı).</p>
            <div class="grid neutral">$gestureCards</div>
            """ else ""}

            <h2>Olay akışı (en yenisi üstte)</h2>
            ${if (events.isEmpty()) """<div class="empty">Henüz olay yok.</div>""" else """<div class="feed">$eventFeed</div>"""}

            <script>
              // Live frame chart powered by uPlot.
              // Polls /perf.json every 2 seconds — re-uses the same data the server-side
              // renders the rest of the page from, so the chart and the cards stay in sync.
              const palette = { ok: '#4ec07a', warn: '#f0b033', bad: '#ef5350', line: '#262a37' };
              let frameChart = null;
              // concurrentOps[i] holds the longest section running while frame[i] rendered, or null.
              // Updated each `refresh()` from the JSON.
              let concurrentOps = [];
              let timelineTimestamps = [];
              const frameHoverEl = () => document.getElementById('frameHover');
              // Color per FPS value: ≥55 fps green (60Hz target), 30-54 yellow, <30 red.
              function colorForFps(fps) {
                if (fps >= 55) return palette.ok;
                if (fps >= 30) return palette.warn;
                return palette.bad;
              }
              // Convert frame duration in ms to instantaneous FPS, capped to 120 to keep the
              // chart readable when the device delivers a sub-millisecond frame.
              function msToFps(ms) {
                const safe = Math.max(0.1, ms);
                return Math.min(120, 1000 / safe);
              }
              // Uncapped variant — used by the third chart so very-fast frames show their true
              // theoretical FPS (e.g. a 1 ms frame → 1000 fps). Y-axis auto-scales.
              function msToFpsUncapped(ms) {
                const safe = Math.max(0.05, ms);
                return 1000 / safe;
              }
              function buildFrameChart(fpsData) {
                const xs = fpsData.map((_, i) => i);
                const opts = {
                  width: document.getElementById('frameChart').clientWidth,
                  height: 160,
                  scales: { x: { time: false }, y: { range: [0, 70] } },
                  axes: [
                    { stroke: palette.line, grid: { stroke: 'rgba(255,255,255,0.04)' }, label: 'frame #', labelSize: 10 },
                    { stroke: palette.line, grid: { stroke: 'rgba(255,255,255,0.04)' }, label: 'fps', labelSize: 18, values: (u, vs) => vs.map(v => v + ' fps') }
                  ],
                  series: [
                    { label: 'frame' },
                    {
                      label: 'fps',
                      // Custom value formatter so the legend says "58.4 fps" not "58.4".
                      value: (u, v) => v == null ? '--' : v.toFixed(1) + ' fps',
                      paths: (u, sIdx, idx0, idx1) => {
                        const stroke = new Path2D();
                        const fills = [];
                        const xData = u.data[0], yData = u.data[1];
                        const w = (u.bbox.width / xData.length) * 0.85;
                        for (let i = idx0; i <= idx1; i++) {
                          const x = u.valToPos(xData[i], 'x', true);
                          const y = u.valToPos(yData[i], 'y', true);
                          const yBase = u.valToPos(0, 'y', true);
                          fills.push({ x: x - w / 2, y, w, h: yBase - y, color: colorForFps(yData[i]) });
                        }
                        return { stroke, fill: null, _bars: fills };
                      },
                      points: { show: false },
                    }
                  ],
                  cursor: {
                    // When the mouse hovers a frame, show "Frame N — X fps — during Y" in the
                    // sibling div. uPlot fires `setCursor` on every mouse move, so we read the
                    // hovered index there.
                    move: (u, l, t) => [l, t],  // identity — let uPlot place the crosshair
                  },
                  hooks: {
                    setCursor: [
                      (u) => {
                        const idx = u.cursor.idx;
                        const el = frameHoverEl();
                        if (el == null) return;
                        if (idx == null || idx < 0 || idx >= u.data[1].length) {
                          el.textContent = 'Bir bar\'ın üstüne gelince hangi operasyonun çalıştığını burada göreceksin.';
                          el.className = 'framehover';
                          return;
                        }
                        const fps = u.data[1][idx];
                        const op = concurrentOps[idx];
                        const ts = timelineTimestamps[idx];
                        const ago = ts ? Math.max(0, Math.round((Date.now() - ts) / 1000)) : null;
                        const cls = fps >= 55 ? 'fhok' : (fps >= 30 ? 'fhwarn' : 'fhbad');
                        const agoTxt = ago != null ? ' · ' + ago + 's önce' : '';
                        const opTxt = op ? ' · sırasında <code>' + op + '</code>' : ' · idle (operasyon yok)';
                        el.innerHTML = '<strong>Frame #' + idx + '</strong> · ' + fps.toFixed(1) + ' fps' + agoTxt + opTxt;
                        el.className = 'framehover ' + cls;
                      }
                    ],
                    // Reference horizontal lines at 60 fps target and 30 fps minimum acceptable.
                    drawAxes: [
                      (u) => {
                        const ctx = u.ctx;
                        const x0 = u.bbox.left;
                        const xN = u.bbox.left + u.bbox.width;
                        ctx.save();
                        ctx.strokeStyle = 'rgba(78,192,122,0.30)';
                        ctx.setLineDash([4, 4]);
                        let y = u.valToPos(60, 'y', true);
                        ctx.beginPath(); ctx.moveTo(x0, y); ctx.lineTo(xN, y); ctx.stroke();
                        ctx.strokeStyle = 'rgba(240,176,51,0.30)';
                        y = u.valToPos(30, 'y', true);
                        ctx.beginPath(); ctx.moveTo(x0, y); ctx.lineTo(xN, y); ctx.stroke();
                        ctx.restore();
                      }
                    ],
                    drawSeries: [
                      (u, sIdx) => {
                        if (sIdx !== 1) return;
                        const ctx = u.ctx;
                        const path = u.series[sIdx]._paths;
                        if (path && path._bars) {
                          for (const b of path._bars) {
                            ctx.fillStyle = b.color;
                            ctx.fillRect(b.x, b.y, b.w, b.h);
                          }
                        }
                      }
                    ]
                  }
                };
                frameChart = new uPlot(opts, [xs, fpsData], document.getElementById('frameChart'));
              }
              // Uncapped instantaneous FPS chart — same data as buildFrameChart, no Y cap.
              let uncappedFpsChart = null;
              function buildUncappedFpsChart(fpsData) {
                const xs = fpsData.map((_, i) => i);
                const maxFps = Math.max(60, ...fpsData);
                const opts = {
                  width: document.getElementById('uncappedFpsChart').clientWidth,
                  height: 160,
                  scales: { x: { time: false }, y: { range: [0, maxFps * 1.1] } },
                  axes: [
                    { stroke: palette.line, grid: { stroke: 'rgba(255,255,255,0.04)' }, label: 'frame #', labelSize: 10 },
                    { stroke: palette.line, grid: { stroke: 'rgba(255,255,255,0.04)' }, label: 'fps (uncapped)', labelSize: 18, values: (u, vs) => vs.map(v => Math.round(v) + ' fps') }
                  ],
                  series: [
                    { label: 'frame' },
                    {
                      label: 'fps',
                      value: (u, v) => v == null ? '--' : v.toFixed(1) + ' fps',
                      // Single colour for all bars — perf comparison is done by HEIGHT here, not colour.
                      paths: (u, sIdx, idx0, idx1) => {
                        const fills = [];
                        const xData = u.data[0], yData = u.data[1];
                        const w = (u.bbox.width / xData.length) * 0.85;
                        for (let i = idx0; i <= idx1; i++) {
                          const x = u.valToPos(xData[i], 'x', true);
                          const y = u.valToPos(yData[i], 'y', true);
                          const yBase = u.valToPos(0, 'y', true);
                          fills.push({ x: x - w / 2, y, w, h: yBase - y });
                        }
                        return { stroke: new Path2D(), fill: null, _bars: fills };
                      },
                      points: { show: false },
                    }
                  ],
                  hooks: {
                    drawSeries: [
                      (u, sIdx) => {
                        if (sIdx !== 1) return;
                        const ctx = u.ctx;
                        const path = u.series[sIdx]._paths;
                        if (path && path._bars) {
                          ctx.fillStyle = '#7c8aff'; // neutral blue — different from the other charts
                          for (const b of path._bars) {
                            ctx.fillRect(b.x, b.y, b.w, b.h);
                          }
                        }
                      }
                    ]
                  }
                };
                uncappedFpsChart = new uPlot(opts, [xs, fpsData], document.getElementById('uncappedFpsChart'));
              }
              // Live FPS chart — single series, wall-clock time on x, fps on y.
              let liveFpsChart = null;
              function buildLiveFpsChart(samples) {
                if (samples.length === 0) return;
                const xs = samples.map(s => s.t / 1000);
                const ys = samples.map(s => s.fps);
                const opts = {
                  width: document.getElementById('liveFpsChart').clientWidth,
                  height: 160,
                  scales: { x: { time: true }, y: { range: [0, 70] } },
                  axes: [
                    { stroke: palette.line },
                    { stroke: palette.line, label: 'fps', labelSize: 18, values: (u, vs) => vs.map(v => v + ' fps') },
                  ],
                  series: [
                    { label: 'time' },
                    {
                      label: 'fps',
                      stroke: palette.ok,
                      width: 2,
                      points: { show: true, size: 4 },
                      value: (u, v) => v == null ? '--' : v.toFixed(1) + ' fps',
                    },
                  ],
                  hooks: {
                    drawAxes: [
                      (u) => {
                        const ctx = u.ctx;
                        const x0 = u.bbox.left;
                        const xN = u.bbox.left + u.bbox.width;
                        ctx.save();
                        ctx.strokeStyle = 'rgba(78,192,122,0.30)';
                        ctx.setLineDash([4, 4]);
                        let y = u.valToPos(60, 'y', true);
                        ctx.beginPath(); ctx.moveTo(x0, y); ctx.lineTo(xN, y); ctx.stroke();
                        ctx.strokeStyle = 'rgba(240,176,51,0.30)';
                        y = u.valToPos(30, 'y', true);
                        ctx.beginPath(); ctx.moveTo(x0, y); ctx.lineTo(xN, y); ctx.stroke();
                        ctx.restore();
                      },
                    ],
                  },
                };
                liveFpsChart = new uPlot(opts, [xs, ys], document.getElementById('liveFpsChart'));
              }
              function colorForFpsHex(fps) {
                if (fps >= 55) return palette.ok;
                if (fps >= 30) return palette.warn;
                return palette.bad;
              }
              // Memory chart — three series (Java heap MB, native MB, PSS MB) over wall-clock time.
              let memChart = null;
              function buildMemChart(samples) {
                if (samples.length === 0) return;
                const xs = samples.map(s => s.t / 1000); // unix seconds
                const java = samples.map(s => s.javaUsedMb);
                const nat = samples.map(s => s.nativeMb);
                const pss = samples.map(s => s.pssMb);
                const opts = {
                  width: document.getElementById('memChart').clientWidth,
                  height: 160,
                  scales: { x: { time: true } },
                  axes: [
                    { stroke: palette.line },
                    { stroke: palette.line, label: 'MB', labelSize: 18, values: (u, vs) => vs.map(v => v + ' MB') },
                  ],
                  series: [
                    { label: 'time' },
                    { label: 'java heap', stroke: palette.ok, width: 2 },
                    { label: 'native heap', stroke: palette.warn, width: 2 },
                    { label: 'PSS', stroke: palette.bad, width: 2 },
                  ],
                };
                memChart = new uPlot(opts, [xs, java, nat, pss], document.getElementById('memChart'));
              }
              async function refresh() {
                try {
                  const r = await fetch('/perf.json');
                  if (!r.ok) return;
                  const d = await r.json();
                  // Frame chart — convert ns frame durations into instantaneous FPS.
                  const tlNs = (d.frames && d.frames.timelineNs) || [];
                  const fpsArr = tlNs.map(ns => msToFps(ns / 1_000_000));
                  const fpsArrUncapped = tlNs.map(ns => msToFpsUncapped(ns / 1_000_000));
                  // Update parallel arrays driving the hover tooltip.
                  concurrentOps = (d.frames && d.frames.timelineConcurrentOps) || new Array(fpsArr.length).fill(null);
                  timelineTimestamps = (d.frames && d.frames.timelineTimestampsMs) || new Array(fpsArr.length).fill(0);
                  if (frameChart) {
                    frameChart.setData([fpsArr.map((_, i) => i), fpsArr]);
                  } else if (fpsArr.length > 0) {
                    buildFrameChart(fpsArr);
                  }
                  // Uncapped chart — auto Y range, peak/avg badges.
                  if (fpsArrUncapped.length > 0) {
                    if (uncappedFpsChart) {
                      // Rebuild on refresh because Y-axis range may have changed dramatically.
                      uncappedFpsChart.destroy();
                      buildUncappedFpsChart(fpsArrUncapped);
                    } else {
                      buildUncappedFpsChart(fpsArrUncapped);
                    }
                    const peakEl = document.getElementById('uncappedFpsMax');
                    const avgEl = document.getElementById('uncappedFpsAvg');
                    if (peakEl && avgEl) {
                      const maxV = Math.max(...fpsArrUncapped);
                      const avgV = fpsArrUncapped.reduce((a, b) => a + b, 0) / fpsArrUncapped.length;
                      peakEl.textContent = maxV.toFixed(0) + ' fps';
                      avgEl.textContent = avgV.toFixed(0) + ' fps';
                    }
                  }
                  // FPS number with severity colour.
                  const fps = (d.frames && d.frames.effectiveFps) || 0;
                  const fpsEl = document.getElementById('fpsValue');
                  if (fpsEl) {
                    if (fps < 1) {
                      fpsEl.textContent = '— idle';
                      fpsEl.className = 'fpsnum idle';
                    } else {
                      fpsEl.textContent = fps.toFixed ? fps.toFixed(1) : fps;
                      fpsEl.className = 'fpsnum ' + (fps >= 55 ? '' : (fps >= 30 ? 'warn' : 'bad'));
                    }
                  }
                  // Live FPS chart — wall-clock 1Hz samples over up to 60s.
                  const live = d.fpsLive || [];
                  if (live.length > 0) {
                    if (liveFpsChart) {
                      liveFpsChart.setData([
                        live.map(s => s.t / 1000),
                        live.map(s => s.fps),
                      ]);
                    } else {
                      buildLiveFpsChart(live);
                    }
                    const latest = live[live.length - 1].fps;
                    const liveEl = document.getElementById('liveFpsValue');
                    if (liveEl) {
                      // Android doesn't render frames when nothing is animating — 0 fps is the
                      // device's actual idle state, not a measurement bug. Show that explicitly so
                      // the user doesn't think the dashboard is broken.
                      if (latest < 1) {
                        liveEl.textContent = '— idle';
                        liveEl.className = 'fpsnum idle';
                      } else {
                        liveEl.textContent = latest.toFixed(1);
                        liveEl.className = 'fpsnum ' + (latest >= 55 ? '' : (latest >= 30 ? 'warn' : 'bad'));
                      }
                    }
                    const fpsArr2 = live.map(s => s.fps);
                    const avg = fpsArr2.reduce((a, b) => a + b, 0) / fpsArr2.length;
                    const minV = Math.min(...fpsArr2);
                    const maxV = Math.max(...fpsArr2);
                    const setText = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v.toFixed(1) + ' fps'; };
                    setText('liveFpsAvg', avg);
                    setText('liveFpsMin', minV);
                    setText('liveFpsMax', maxV);
                  }
                  // Memory chart — series over wall-clock seconds.
                  const mem = d.memory || [];
                  if (mem.length > 0) {
                    if (memChart) {
                      memChart.setData([
                        mem.map(s => s.t / 1000),
                        mem.map(s => s.javaUsedMb),
                        mem.map(s => s.nativeMb),
                        mem.map(s => s.pssMb),
                      ]);
                    } else {
                      buildMemChart(mem);
                    }
                  }
                } catch (e) { /* ignore network blips */ }
              }
              // Initial render with whatever the server gave us, then poll once per second so the
              // live FPS number/chart actually feel alive.
              refresh();
              setInterval(refresh, 1000);
              // Reload the rest of the page (cards, findings, feed) every 15s to keep them fresh
              // — uPlot charts update via the JSON poll above without needing a full reload.
              setTimeout(() => location.reload(), 15000);
            </script>

            </body></html>
        """.trimIndent()
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Diagnostics — rule-based finding generator
    // ────────────────────────────────────────────────────────────────────────

    private data class Finding(
        val severity: String, // "bad" | "warn" | "info"
        val title: String,
        val body: String,
    )

    private fun runDiagnostics(
        sections: Map<String, PerfRegistry.Stats>,
        frames: PerfRegistry.FrameStats,
        events: List<PerfRegistry.Event>,
    ): List<Finding> {
        val out = mutableListOf<Finding>()

        // Rule 1 — child dominates parent (bottleneck inside compound op)
        for ((name, parent) in sections) {
            val children = sections.entries.filter {
                it.key.startsWith("$name.") && it.key.removePrefix("$name.").contains('.').not()
            }
            if (children.isEmpty() || parent.medianNs <= 0) continue
            val worst = children.maxByOrNull { it.value.medianNs } ?: continue
            val pct = (worst.value.medianNs * 100 / parent.medianNs).coerceAtMost(100)
            if (pct >= 50) {
                val short = worst.key.removePrefix("$name.")
                out += Finding(
                    severity = if (pct >= 75) "bad" else "warn",
                    title = "<code>$short</code>, <code>$name</code>'in darboğazı",
                    body = "Parent operasyonun median süresinin %$pct'i bu alt-section'da geçiyor " +
                        "(${formatNs(worst.value.medianNs)} / ${formatNs(parent.medianNs)}). Optimizasyona burada başla.",
                )
            }
        }

        // Rule 2 — high variance
        for ((name, s) in sections) {
            if (s.count < 3 || s.medianNs <= 0) continue
            val ratio = s.maxNs.toDouble() / s.medianNs.toDouble()
            if (ratio >= 5.0) {
                out += Finding(
                    severity = "info",
                    title = "<code>$name</code> varyansı yüksek",
                    body = "min ${formatNs(s.minNs)} · median ${formatNs(s.medianNs)} · max ${formatNs(s.maxNs)} " +
                        "(median'ın ${"%.1f".format(ratio)}× katı). Cold path / cache miss / büyük girdi olabilir.",
                )
            }
        }

        // Rule 3 — freezes correlated with a section
        if (frames.freezes > 0) {
            val freezeFrames = events.filterIsInstance<PerfRegistry.Event.Frame>()
                .filter { it.durationNs >= PerfRegistry.FREEZE_THRESHOLD_NS }
            val correlations = freezeFrames.mapNotNull { fe -> correlateFrameWithSection(fe, events) }
            val freezeThresholdMs = PerfRegistry.FREEZE_THRESHOLD_NS / 1_000_000L
            if (correlations.isNotEmpty()) {
                val (sec, hits) = correlations.groupingBy { it }.eachCount().entries.maxByOrNull { it.value }!!
                out += Finding(
                    severity = "bad",
                    title = "Donmalar <code>$sec</code> sırasında",
                    body = "${frames.freezes} donmadan $hits tanesi bu operasyon çalışırken oldu " +
                        "(${freezeThresholdMs}ms+ frame). En uzun frame ${formatNs(frames.maxFrameNs)}.",
                )
            } else {
                out += Finding(
                    severity = "bad",
                    title = "${frames.freezes} donma tespit edildi",
                    body = "En uzun frame ${formatNs(frames.maxFrameNs)}. Hangi section'da olduğu bulunamadı — " +
                        "hot path'te <code>trace { ... }</code> sarmaları yetersiz olabilir.",
                )
            }
        }

        // Rule 4 — jank concentrated in a single UI state
        if (frames.recentJank.size >= 5) {
            val byState = frames.recentJank.groupingBy { it.uiState ?: "?" }.eachCount()
            val (state, count) = byState.entries.maxByOrNull { it.value } ?: return out
            if (count >= frames.recentJank.size / 2 && state != "?") {
                out += Finding(
                    severity = "warn",
                    title = "Jank <code>$state</code> ekranında yoğun",
                    body = "Son ${frames.recentJank.size} jank frame'in $count tanesi bu state'te. " +
                        "Recomposition / overdraw / main-thread iş şüphesi.",
                )
            }
        }

        // Rule 5 — overall jank rate high
        if (frames.totalFrames >= 100 && frames.jankPercentage >= 10f) {
            out += Finding(
                severity = "warn",
                title = "Genel jank oranı yüksek",
                body = "${"%.1f".format(frames.jankPercentage)}% frame jank " +
                    "(${frames.jankyFrames}/${frames.totalFrames}). 60fps hedefi tutmuyor.",
            )
        }

        // Rule 6 — slow operations (median > 200ms)
        val slow = sections.entries.filter { it.value.medianMs > 200 && it.value.count >= 2 }
            .sortedByDescending { it.value.medianNs }
        if (slow.isNotEmpty()) {
            val list = slow.take(3).joinToString(", ") {
                "<code>${it.key}</code> (${formatNs(it.value.medianNs)})"
            }
            out += Finding(
                severity = if (slow.first().value.medianMs > 500) "bad" else "warn",
                title = "Yavaş operasyon${if (slow.size == 1) "" else "lar"}",
                body = "Median > 200ms: $list. UX'te kullanıcının hissedeceği seviyede.",
            )
        }

        // Rule 7 — Matrix issues (ANR/leak/io/etc.). Bucket by kind, surface latest.
        val matrixIssues = events.filterIsInstance<PerfRegistry.Event.MatrixIssue>()
        if (matrixIssues.isNotEmpty()) {
            val byKind = matrixIssues.groupBy { it.kind }
            for ((kind, issues) in byKind) {
                val severity = when (kind) {
                    "anr", "evil_method" -> "bad"
                    "leak", "io" -> "warn"
                    else -> "info"
                }
                val latest = issues.first() // events come newest-first
                val countSuffix = if (issues.size > 1) " (×${issues.size})" else ""
                val detailSuffix = if (latest.details.isNotBlank()) {
                    " · detay: <code>${htmlEscape(latest.details.take(200))}…</code>"
                } else "."
                out += Finding(
                    severity = severity,
                    title = "${latest.title}$countSuffix",
                    body = "Tencent Matrix tarafından otomatik tespit edildi$detailSuffix",
                )
            }
        }

        return out
    }

    /**
     * Find a section event that was running while [frame] was being rendered. Heuristic:
     * pick the section that ended within [CORRELATION_WINDOW_MS] *after* the frame timestamp,
     * because [PerfRegistry] timestamps are recorded at end-of-section. We pick the closest in
     * time, since multiple sections may be in flight.
     */
    private fun correlateFrameWithSection(
        frame: PerfRegistry.Event.Frame,
        events: List<PerfRegistry.Event>,
    ): String? {
        val window = 250L
        var best: PerfRegistry.Event.Section? = null
        var bestDist = Long.MAX_VALUE
        for (e in events) {
            if (e !is PerfRegistry.Event.Section) continue
            val dist = e.timestampMs - frame.timestampMs
            if (dist < -window || dist > window) continue
            if (kotlin.math.abs(dist) < bestDist) {
                bestDist = kotlin.math.abs(dist)
                best = e
            }
        }
        return best?.name
    }

    private fun renderFindings(findings: List<Finding>): String =
        findings.joinToString(separator = "\n") { f ->
            val ico = when (f.severity) { "bad" -> "🔥"; "warn" -> "⚠"; else -> "ℹ" }
            """
            <div class="finding ${f.severity}">
              <div class="ico">$ico</div>
              <div>
                <div class="ftitle">${f.title}</div>
                <div class="fbody">${f.body}</div>
              </div>
            </div>
            """.trimIndent()
        }

    // ────────────────────────────────────────────────────────────────────────
    //  Waterfall — for each parent section, show its children as % of parent
    // ────────────────────────────────────────────────────────────────────────

    private fun renderWaterfalls(sections: Map<String, PerfRegistry.Stats>): String {
        // A section is a "parent" if it has at least one child whose name is exactly `<parent>.X`.
        val parents = sections.entries.filter { (name, _) ->
            sections.keys.any { it.startsWith("$name.") && it.removePrefix("$name.").contains('.').not() }
        }.sortedByDescending { it.value.count * it.value.medianMs }
        if (parents.isEmpty()) return ""
        return parents.joinToString(separator = "\n") { (parentName, parent) ->
            val children = sections.entries
                .filter { it.key.startsWith("$parentName.") && it.key.removePrefix("$parentName.").contains('.').not() }
                .sortedByDescending { it.value.medianNs }
            val rows = children.joinToString("\n") { (cName, cs) ->
                val pct = (cs.medianNs * 100 / parent.medianNs.coerceAtLeast(1L)).coerceAtMost(100)
                val cls = when {
                    pct >= 50 -> "bad"
                    pct >= 25 -> "warn"
                    else -> "ok"
                }
                val short = cName.removePrefix("$parentName.")
                """
                <div class="row $cls">
                  <div class="lbl">${htmlEscape(short)}</div>
                  <div class="bar"><span style="width:${pct}%"></span></div>
                  <div class="ms">${formatNs(cs.medianNs)}</div>
                  <div class="pct">%$pct</div>
                </div>
                """.trimIndent()
            }
            val unaccountedRow = if (parent.medianNs - children.sumOf { it.value.medianNs } > 0) {
                val unaccountedNs = parent.medianNs - children.sumOf { it.value.medianNs }
                val pct = (unaccountedNs * 100 / parent.medianNs.coerceAtLeast(1L)).coerceAtMost(100)
                """
                <div class="row ok">
                  <div class="lbl">(diğer / ölçülmeyen)</div>
                  <div class="bar"><span style="width:${pct}%"></span></div>
                  <div class="ms">${formatNs(unaccountedNs)}</div>
                  <div class="pct">%$pct</div>
                </div>
                """.trimIndent()
            } else ""
            """
            <div class="waterfall">
              <div class="head">
                <div class="ttl">${htmlEscape(parentName)}</div>
                <div class="total">median ${formatNs(parent.medianNs)} · ×${parent.count} · p95 ${formatNs(parent.p95Ns)} · max ${formatNs(parent.maxNs)}</div>
              </div>
              $rows
              $unaccountedRow
            </div>
            """.trimIndent()
        }
    }

    private data class Verdict(val cls: String, val badge: String, val title: String, val summary: String)

    private fun computeVerdict(sections: Map<String, PerfRegistry.Stats>, frames: PerfRegistry.FrameStats): Verdict {
        val slowOps = sections.values.count { it.medianMs > 200 }
        val anyData = sections.isNotEmpty() || frames.totalFrames > 0
        return when {
            !anyData -> Verdict(
                cls = "ok",
                badge = "—",
                title = "Henüz veri yok",
                summary = "Telefonda uygulamayı kullan, ölçümler buraya akacak.",
            )
            frames.freezes > 0 -> Verdict(
                cls = "bad",
                badge = "🔥",
                title = "Donma tespit edildi",
                summary = "${frames.freezes} kez ekran ${PerfRegistry.FREEZE_THRESHOLD_NS / 1_000_000L}ms üstü donmuş · " +
                    "en uzun frame ${formatNs(frames.maxFrameNs)} · ${frames.jankyFrames} jank frame · $slowOps yavaş operasyon",
            )
            slowOps > 0 || frames.jankPercentage >= 5f -> Verdict(
                cls = "warn",
                badge = "⚠",
                title = "Yavaşlama var",
                summary = "$slowOps yavaş operasyon · ${frames.jankyFrames}/${frames.totalFrames} jank frame " +
                    "(${"%.1f".format(frames.jankPercentage)}%) · en uzun frame ${formatNs(frames.maxFrameNs)}",
            )
            else -> Verdict(
                cls = "ok",
                badge = "✓",
                title = "Akıcı",
                summary = "${sections.size} operasyon ölçüldü · ${frames.totalFrames} frame · " +
                    "jank ${"%.1f".format(frames.jankPercentage)}% · en uzun frame ${formatNs(frames.maxFrameNs)}",
            )
        }
    }

    /**
     * Categorise a section by its name so the dashboard can apply the right severity bands and
     * group the cards in a meaningful way.
     */
    private enum class Category { RENDER, STATE, GESTURE }

    private fun categorize(name: String): Category {
        // Gesture tags: stroke / move / gesture / tap (user-paced; raw duration isn't a perf signal).
        // ".stroke.commit" is the state push for the finished stroke and IS state-mutation work.
        if (name.endsWith(".gesture") ||
            name.endsWith(".gesture.cancel") ||
            name.endsWith(".move") ||
            name.endsWith(".tap") ||
            (name.endsWith(".stroke") && !name.endsWith(".stroke.commit"))
        ) {
            return Category.GESTURE
        }
        // Render tags: anything inside an export/decode/encode/draw/transform pipeline.
        if (name.contains(".export") ||
            name.contains(".decode") ||
            name.contains(".encode") ||
            name.contains(".transform") ||
            name.endsWith(".export.draw") ||
            name.endsWith(".export.crop")
        ) {
            return Category.RENDER
        }
        // Everything else (tool.select, color.change, undo, addText, …) is a state-level mutation.
        return Category.STATE
    }

    private fun renderSectionCard(name: String, s: PerfRegistry.Stats, category: Category): String {
        val severity = severityFor(s, category)
        val histogram = renderHistogram(s)
        val stddevNs = computeStddev(s.recentSamplesNs)
        val cv = if (s.medianNs > 0) (stddevNs / s.medianNs.toDouble()) * 100 else 0.0
        val totalImpactNs = s.count * s.medianNs
        val (badgeClass, badgeText) = when (category) {
            Category.RENDER -> "render" to "🎨 render"
            Category.STATE -> "state" to "⚡ state"
            Category.GESTURE -> "gesture" to "👆 user gesture"
        }
        val fpsRow = renderFpsRow(s)
        return """
            <div class="card $severity">
              <span class="catbadge $badgeClass">$badgeText</span>
              <div class="name">${htmlEscape(name)}</div>
              <div class="big">${formatNs(s.medianNs)}<small> · median</small></div>
              ${renderSparkline(s.recentSamplesNs)}
              <div class="stats">×${s.count} · p95 ${formatNs(s.p95Ns)} · max ${formatNs(s.maxNs)} · min ${formatNs(s.minNs)}</div>
              $histogram
              $fpsRow
              <div class="extra">
                σ ${formatNs(stddevNs.toLong())} · cv ${"%.0f".format(cv)}% · ortalama ${formatNs(s.averageNs)} · toplam ${formatNs(s.totalNs)} · etki ${formatNs(totalImpactNs)}
              </div>
            </div>
        """.trimIndent()
    }

    /**
     * Per-section FPS line. Only shows when the section was at least one frame long during one of
     * its runs — sub-frame ops (like 0.2 ms colour swatch picks) deliberately have no FPS value.
     *
     * Colour bands: ≥55 fps green (60 Hz target), 30-54 yellow, <30 red.
     */
    private fun renderFpsRow(s: PerfRegistry.Stats): String {
        if (!s.hasFps) return ""
        val avg = s.fpsAverage
        val cls = when {
            avg >= 55f -> "fps-ok"
            avg >= 30f -> "fps-warn"
            else -> "fps-bad"
        }
        return """
            <div class="fpsrow $cls">
              🎬 FPS during op
              <b>${"%.1f".format(avg)}</b> avg
              <span class="fpsdetail">min ${"%.1f".format(s.fpsMin)} · max ${"%.1f".format(s.fpsMax)} · ×${s.fpsCount} run</span>
            </div>
        """.trimIndent()
    }

    /**
     * Severity thresholds vary by category:
     * - **Render**: standard 50/200 ms bands (the user actually waits for these).
     * - **State**: tighter 1/5 ms bands (single-statement mutations should be sub-ms).
     * - **Gesture**: always neutral — duration reflects how long the user dragged, not perf.
     */
    private fun severityFor(s: PerfRegistry.Stats, category: Category): String = when (category) {
        Category.RENDER -> when {
            s.medianMs > 200 -> "bad"
            s.medianMs > 50 -> "warn"
            else -> "ok"
        }
        Category.STATE -> when {
            s.medianNs > 5_000_000 -> "bad"   // > 5 ms
            s.medianNs > 1_000_000 -> "warn"  // > 1 ms
            else -> "ok"
        }
        Category.GESTURE -> "ok"
    }

    /**
     * Pick the longest section event whose `[end - durationMs, end]` interval contains
     * [frameTimestampMs] (with a small grace window either side to catch frames that fire just
     * before/after the gesture's recorded boundaries). "Longest" so an enclosing gesture (e.g.
     * a 2-second draw stroke) wins over a 5 ms text.commit that happened to fire mid-stroke —
     * much more useful for "what was I doing while this frame ran?".
     *
     * Falls back to the *closest* section within [GRACE_FALLBACK_MS] when no containing section
     * exists — useful for frames rendered right around a render-step boundary.
     */
    private fun findLongestContainingSection(
        frameTimestampMs: Long,
        sections: List<PerfRegistry.Event.Section>,
    ): String? {
        val grace = 100L          // ms of slack on either side of a section's interval
        val fallback = 500L       // closest-section fallback window
        var best: PerfRegistry.Event.Section? = null
        var bestDurMs = 0L
        var nearest: PerfRegistry.Event.Section? = null
        var nearestDist = Long.MAX_VALUE
        for (s in sections) {
            val sectionDurMs = s.durationMs
            val start = s.timestampMs - sectionDurMs
            if (frameTimestampMs in (start - grace)..(s.timestampMs + grace) && sectionDurMs > bestDurMs) {
                best = s
                bestDurMs = sectionDurMs
            }
            // Track nearest as a fallback in case nothing contains the frame.
            val dist = if (frameTimestampMs < start) start - frameTimestampMs
                else if (frameTimestampMs > s.timestampMs) frameTimestampMs - s.timestampMs
                else 0L
            if (dist < nearestDist) {
                nearest = s
                nearestDist = dist
            }
        }
        return best?.name ?: nearest?.takeIf { nearestDist <= fallback }?.let { "${it.name} (yakın)" }
    }

    /** Compact 10-bucket histogram of recent samples; bucket colors mirror the card severity. */
    private fun renderHistogram(s: PerfRegistry.Stats): String {
        val samples = s.recentSamplesNs
        if (samples.size < 4) return ""
        val buckets = 10
        val max = samples.max().coerceAtLeast(1L)
        val counts = IntArray(buckets)
        for (v in samples) {
            val idx = ((v.toDouble() / max.toDouble()) * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
            counts[idx]++
        }
        val maxCount = counts.max()
        val bars = (0 until buckets).joinToString("") { i ->
            val h = if (maxCount == 0) 1 else (counts[i] * 100 / maxCount).coerceAtLeast(if (counts[i] > 0) 8 else 4)
            """<span style="height:${h}%"></span>"""
        }
        return """
            <div class="hist">$bars</div>
            <div class="histlabels"><span>0</span><span>${formatNs(max)}</span></div>
        """.trimIndent()
    }

    private fun computeStddev(samples: LongArray): Double {
        if (samples.size < 2) return 0.0
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        return kotlin.math.sqrt(variance)
    }

    /** Threshold bands per metric, in MB. */
    private object MemoryThresholds {
        // Java heap as % of max — same bands across devices.
        const val JAVA_PCT_WARN = 25
        const val JAVA_PCT_BAD = 75
        // Native heap absolute MB — image-heavy apps live around 100-200 MB; >300 is suspect.
        const val NATIVE_MB_WARN = 150
        const val NATIVE_MB_BAD = 300
        // Total PSS — overall process RAM. <500 normal, >800 the OS may start trimming.
        const val PSS_MB_WARN = 500
        const val PSS_MB_BAD = 800
        // A sudden drop of this many MB between consecutive samples = GC / cache eviction.
        const val GC_DROP_MB = 8L
        // Window for "rising trend" = leak hint.
        const val RISING_WINDOW = 6
        const val RISING_DELTA_MB = 5L
    }

    private data class MemoryVerdict(
        val cls: String,        // "ok" | "warn" | "bad"
        val badge: String,
        val title: String,
        val summary: String,
    )

    private fun computeMemoryVerdict(snaps: List<PerfRegistry.MemorySnapshot>): MemoryVerdict? {
        val latest = snaps.lastOrNull() ?: return null
        val rising = snaps.takeLast(MemoryThresholds.RISING_WINDOW).let { tail ->
            tail.size >= MemoryThresholds.RISING_WINDOW &&
                tail.zipWithNext().all { (a, b) -> b.javaHeapUsedMb >= a.javaHeapUsedMb } &&
                (tail.last().javaHeapUsedMb - tail.first().javaHeapUsedMb) >= MemoryThresholds.RISING_DELTA_MB
        }
        val javaPctBad = latest.javaHeapUsagePercent >= MemoryThresholds.JAVA_PCT_BAD
        val nativeBad = latest.nativeHeapAllocatedMb >= MemoryThresholds.NATIVE_MB_BAD
        val pssBad = latest.totalPssMb >= MemoryThresholds.PSS_MB_BAD
        val javaPctWarn = latest.javaHeapUsagePercent >= MemoryThresholds.JAVA_PCT_WARN
        val nativeWarn = latest.nativeHeapAllocatedMb >= MemoryThresholds.NATIVE_MB_WARN
        val pssWarn = latest.totalPssMb >= MemoryThresholds.PSS_MB_WARN

        return when {
            javaPctBad || nativeBad || pssBad -> MemoryVerdict(
                cls = "bad",
                badge = "🔥",
                title = "Kritik bellek kullanımı",
                summary = buildString {
                    val issues = mutableListOf<String>()
                    if (javaPctBad) issues += "Java heap %${latest.javaHeapUsagePercent} (eşik %${MemoryThresholds.JAVA_PCT_BAD})"
                    if (nativeBad) issues += "Native ${latest.nativeHeapAllocatedMb}MB (eşik ${MemoryThresholds.NATIVE_MB_BAD}MB)"
                    if (pssBad) issues += "PSS ${latest.totalPssMb}MB (eşik ${MemoryThresholds.PSS_MB_BAD}MB)"
                    append(issues.joinToString(" · "))
                    if (rising) append(" · ↗ ayrıca tutarlı artış trendi")
                },
            )
            rising -> MemoryVerdict(
                cls = "bad",
                badge = "💧",
                title = "Bellek sızıntısı şüphesi",
                summary = "Java heap ${MemoryThresholds.RISING_WINDOW} ardışık örnekte sürekli yükseliyor (≥${MemoryThresholds.RISING_DELTA_MB}MB delta). GC yapmadan büyüme = retained ref şüphesi.",
            )
            javaPctWarn || nativeWarn || pssWarn -> MemoryVerdict(
                cls = "warn",
                badge = "⚠",
                title = "Yüksek bellek kullanımı",
                summary = buildString {
                    val notes = mutableListOf<String>()
                    if (javaPctWarn) notes += "Java %${latest.javaHeapUsagePercent}"
                    if (nativeWarn) notes += "Native ${latest.nativeHeapAllocatedMb}MB"
                    if (pssWarn) notes += "PSS ${latest.totalPssMb}MB"
                    append("Yüksek ama henüz kritik değil: ")
                    append(notes.joinToString(" · "))
                },
            )
            else -> MemoryVerdict(
                cls = "ok",
                badge = "✓",
                title = "Bellek sağlıklı",
                summary = "Java %${latest.javaHeapUsagePercent} · Native ${latest.nativeHeapAllocatedMb}MB · PSS ${latest.totalPssMb}MB · ${snaps.size} örnek alındı",
            )
        }
    }

    /** GC events = sudden drops in Java heap between consecutive samples. Useful health signal —
     *  *no* GC drops over time + rising heap is a stronger leak signal than rising heap alone. */
    private fun detectGcDrops(snaps: List<PerfRegistry.MemorySnapshot>): List<Pair<PerfRegistry.MemorySnapshot, Long>> {
        if (snaps.size < 2) return emptyList()
        val out = mutableListOf<Pair<PerfRegistry.MemorySnapshot, Long>>()
        for (i in 1 until snaps.size) {
            val prev = snaps[i - 1]
            val curr = snaps[i]
            val drop = prev.javaHeapUsedMb - curr.javaHeapUsedMb
            if (drop >= MemoryThresholds.GC_DROP_MB) out += curr to drop
        }
        return out
    }

    private fun renderMemoryStatsHtml(): String {
        val snaps = PerfRegistry.memorySnapshot()
        val latest = snaps.lastOrNull() ?: return """<div class="stat">Henüz örnek yok — 1 saniye bekle.</div>"""

        val javaCls = when {
            latest.javaHeapUsagePercent >= MemoryThresholds.JAVA_PCT_BAD -> "bad"
            latest.javaHeapUsagePercent >= MemoryThresholds.JAVA_PCT_WARN -> "warn"
            else -> "ok"
        }
        val nativeCls = when {
            latest.nativeHeapAllocatedMb >= MemoryThresholds.NATIVE_MB_BAD -> "bad"
            latest.nativeHeapAllocatedMb >= MemoryThresholds.NATIVE_MB_WARN -> "warn"
            else -> "ok"
        }
        val pssCls = when {
            latest.totalPssMb >= MemoryThresholds.PSS_MB_BAD -> "bad"
            latest.totalPssMb >= MemoryThresholds.PSS_MB_WARN -> "warn"
            else -> "ok"
        }
        val headroomMb = (latest.javaHeapMaxMb - latest.javaHeapUsedMb).coerceAtLeast(0L)
        val gcDrops = detectGcDrops(snaps)
        val gcSuffix = if (gcDrops.isEmpty()) "—" else "${gcDrops.size} adet GC drop"
        return """
            <span class="stat $javaCls">Java heap <b>${latest.javaHeapUsedMb} MB</b> / ${latest.javaHeapMaxMb} MB (${latest.javaHeapUsagePercent}%)</span>
            <span class="stat $nativeCls">Native heap <b>${latest.nativeHeapAllocatedMb} MB</b></span>
            <span class="stat $pssCls">PSS <b>${latest.totalPssMb} MB</b></span>
            <span class="stat">Headroom <b>${headroomMb} MB</b><small> (OOM'a kalan)</small></span>
            <span class="stat">GC <b>$gcSuffix</b></span>
            <span class="stat">Trend <b>${snaps.size}</b> örnek</span>
        """.trimIndent()
    }

    /**
     * "Şu an ne oluyor" — last N section completions with their per-run FPS, paired with a
     * jank/freeze line whenever a Frame event sits between them. Designed to read top-to-bottom
     * like a story: "draw stroke → 58 fps", "jank!", "text move → 45 fps".
     */
    private fun renderRecentActivityWithFps(events: List<PerfRegistry.Event>): String {
        val recent = events.take(15)
        if (recent.isEmpty()) return ""
        val rows = recent.joinToString("\n") { e ->
            when (e) {
                is PerfRegistry.Event.Section -> {
                    val cls = when {
                        !e.hasFps -> "neutral"
                        e.fpsDuringSection >= 55f -> "ok"
                        e.fpsDuringSection >= 30f -> "warn"
                        else -> "bad"
                    }
                    val fpsTxt = if (e.hasFps) {
                        """<span class="actfps">${"%.1f".format(e.fpsDuringSection)} fps</span>"""
                    } else {
                        """<span class="actfps muted">— fps yok (sub-frame)</span>"""
                    }
                    """
                    <div class="actrow $cls">
                      <span class="actts">${formatTs(e.timestampMs)}</span>
                      <span class="actname">${htmlEscape(e.name)}</span>
                      <span class="actdur">${formatNs(e.durationNs)}</span>
                      $fpsTxt
                    </div>
                    """.trimIndent()
                }
                is PerfRegistry.Event.Frame -> {
                    val isFreeze = e.durationNs >= PerfRegistry.FREEZE_THRESHOLD_NS
                    val fps = if (e.durationMs > 0) (1000f / e.durationMs).toFloat() else 0f
                    val cls = if (isFreeze) "bad" else "warn"
                    val ico = if (isFreeze) "🔥" else "⚠"
                    val label = if (isFreeze) "DONMA" else "jank frame"
                    val state = e.uiState?.let { """ <span class="muted">($it)</span>""" } ?: ""
                    """
                    <div class="actrow $cls">
                      <span class="actts">${formatTs(e.timestampMs)}</span>
                      <span class="actname">$ico $label$state</span>
                      <span class="actdur">${e.durationMs} ms</span>
                      <span class="actfps">${"%.1f".format(fps)} fps</span>
                    </div>
                    """.trimIndent()
                }
                is PerfRegistry.Event.MatrixIssue -> ""
            }
        }
        return """
            <h2>📜 Şu an ne oluyor — son ${recent.size} olay (en yenisi üstte)</h2>
            <p class="cathint">Her satır bir operasyonun süresi + o sürede ölçülen FPS'i gösterir. <code>jank frame</code> ve <code>DONMA</code> satırları frame'in tek başına FPS'ini söyler.</p>
            <div class="activity">$rows</div>
        """.trimIndent()
    }

    /**
     * Short Turkish description for each known section name suffix. Looked up by walking from
     * the most-specific suffix (e.g. `imageeditor.modular.crop.gesture`) toward the most generic
     * (e.g. `text.editor.open`). Unknown sections return null and the row renders without help.
     */
    private val sectionDescriptions: List<Pair<String, String>> = listOf(
        // Editor lifecycle / screen
        "screen.session" to "Editor ekranının toplam açık kalma süresi (ses., disposal'da kapanır)",
        "screen.image.decode" to "Açılışta kaynak URI'nin BitmapFactory ile boyutlarının okunması",

        // Text editor lifecycle
        "text.editor.open" to "Text editor açılış: layout + focus + IME show",
        "text.editor.dismiss" to "Text editor kapanışı (X butonu / back)",
        "text.editor.submit" to "Text editor onay (✓ butonu) → state'e text item commit'i",
        "text.editor.delete" to "Text editor delete butonu — mevcut item'ı silme",
        "text.editor.typing" to "Text editor toplam typing session süresi (open → submit/dismiss)",
        "text.editor.keystroke" to "Her karakter girildiğinde fire eden state update'i",
        "text.editor.color.tap" to "Text editor color palette swatch'a tıklama",
        "text.editor.align.cycle" to "Text editor align butonu (left → center → right cycle)",
        "text.editor.size.commit" to "Text editor font size slider'ı bırakıldığında final size commit",

        // Text overlay (canvas üstünde sürükleme)
        "text.add" to "State'e yeni TextItem ekleme (instant state mutation)",
        "text.update" to "Mevcut text item alanlarını güncelleme",
        "text.remove" to "Text item'ı state'ten silme",
        "text.move" to "Canvas üstünde text item'ı sürükleme süresi (multi-second gesture)",
        "text.tap" to "Text item'a tap (düzenleme/silme tetikleyici)",

        // Drawing
        "draw.stroke.commit" to "Stroke bittikten sonra state'e ekleme (instant)",
        "draw.stroke" to "Çizim stroke'u (touch down → touch up). FPS = drag akıcılığı",
        "draw.color.change" to "Brush rengi değiştirme (instant state mutation)",
        "draw.width.change" to "Brush kalınlığı değiştirme (instant state mutation)",
        "draw.undo" to "Son stroke'u geri alma",
        "draw.clear" to "Tüm stroke'ları silme",
        "highlighter.color.change" to "Highlighter rengi değiştirme",
        "highlighter.width.change" to "Highlighter kalınlığı değiştirme",

        // Crop
        "crop.gesture" to "Crop corner sürükleme süresi (touch down → touch up). FPS = drag akıcılığı",
        "crop.process" to "uCrop'un crop+save işi (decode + transform + crop + JPEG encode)",
        "crop.apply" to "Aspect ratio değişikliği veya manuel rect commit (instant state)",
        "crop.reset" to "Crop'u tam boyuta sıfırlama (instant state)",

        // Tool selection
        "tool.select" to "Toolbar'dan farklı tool'a geçiş (instant state mutation)",

        // Transforms
        "transform.rotate" to "Görüntüyü 90° döndürme (instant state mutation)",
        "transform.flip" to "Görüntüyü horizontal flip (instant state mutation)",

        // Brush (PhotoEditor)
        "brush.color.change" to "PhotoEditor brush rengi değişikliği",
        "brush.width.change" to "PhotoEditor brush kalınlığı değişikliği",
        "filter.apply" to "PhotoEditor filter (sepia/gri/vs.) uygulama",
        "undo" to "Genel undo butonu",
        "redo" to "Genel redo butonu",
        "clear" to "Genel clear butonu",

        // Export pipeline
        "export.decode" to "Save: kaynak bitmap'i BitmapFactory ile okuma",
        "export.transform" to "Save: rotate + flip uygulama (yeni bitmap allocate)",
        "export.draw.paths" to "Save: çizim path'lerini bitmap'e basma (Path/Paint)",
        "export.draw.text" to "Save: yazıları bitmap'e basma",
        "export.draw.strokes" to "Save: Ink stroke'ları bitmap'e basma",
        "export.crop" to "Save: cropRect'e göre bitmap'i kırpma",
        "export.encode" to "Save: bitmap'i JPEG/PNG'ye encode + diske yazma",
        "export" to "Save işleminin toplam süresi (decode → encode end-to-end)",
    )

    private fun describeSection(name: String): String? {
        // Walk known suffixes from longest to shortest; the first that the section name ends with wins.
        return sectionDescriptions
            .sortedByDescending { it.first.length }
            .firstOrNull { (suffix, _) -> name.endsWith(".$suffix") || name == suffix }
            ?.second
    }

    /**
     * Per-tool table — every traced section the user has exercised. Two row styles:
     *   - **FPS rows** (gestures + renders ≥33 ms): avg fps + min/max + verdict (jank/freeze/smooth)
     *   - **Instant rows** (state mutations under one frame): median duration with "sub-frame" tag
     *
     * Sorting: worst FPS first, then instant rows by descending median duration.
     */
    private fun renderPerToolFpsHtml(sections: Map<String, PerfRegistry.Stats>): String {
        if (sections.isEmpty()) {
            return """
                <h2>🔧 Per-tool FPS — hangi tool'da donma var?</h2>
                <p class="cathint">Henüz hiç trace yok. Editor'leri kullan, satırlar burada belirir.</p>
                <div class="empty">Veri yok.</div>
            """.trimIndent()
        }
        val (withFps, instant) = sections.entries.partition { it.value.hasFps }
        val fpsRows = withFps.sortedBy { it.value.fpsAverage }.joinToString("\n") { (name, s) ->
            val avg = s.fpsAverage
            val cls = when {
                avg < 30f -> "bad"
                avg < 55f -> "warn"
                else -> "ok"
            }
            val ico = when {
                avg < 30f -> "🔥"
                avg < 55f -> "⚠"
                else -> "✓"
            }
            val verdict = when {
                avg < 30f -> "donma riski"
                avg < 55f -> "jank var"
                else -> "akıcı"
            }
            val description = describeSection(name)?.let { """<div class="ptdesc">${htmlEscape(it)}</div>""" } ?: ""
            """
            <tr class="ptrow $cls">
              <td class="ptico">$ico</td>
              <td class="ptname"><code>${htmlEscape(name)}</code>$description</td>
              <td class="ptfps"><b>${"%.1f".format(s.fpsAverage)}</b><span class="ptunit">fps</span></td>
              <td class="ptmin">min ${"%.1f".format(s.fpsMin)}</td>
              <td class="ptmax">max ${"%.1f".format(s.fpsMax)}</td>
              <td class="ptcount">×${s.fpsCount}</td>
              <td class="ptverdict">$verdict</td>
            </tr>
            """.trimIndent()
        }
        // Instant rows — sub-frame state mutations. Show median duration; "sub-frame" verdict because
        // they're too short to introduce jank by themselves.
        val instantRows = instant.sortedByDescending { it.value.medianNs }.joinToString("\n") { (name, s) ->
            val isReallyInstant = s.medianNs < 16_666_667L
            val cls = if (isReallyInstant) "neutral" else "warn"
            val ico = if (isReallyInstant) "⚡" else "⚠"
            val verdict = if (isReallyInstant) "instant (sub-frame)" else "kısa ama 16ms+ — daha fazla run ile kesin görelim"
            val description = describeSection(name)?.let { """<div class="ptdesc">${htmlEscape(it)}</div>""" } ?: ""
            """
            <tr class="ptrow $cls">
              <td class="ptico">$ico</td>
              <td class="ptname"><code>${htmlEscape(name)}</code>$description</td>
              <td class="ptfps"><b>${formatNs(s.medianNs)}</b></td>
              <td class="ptmin">min ${formatNs(s.minNs)}</td>
              <td class="ptmax">max ${formatNs(s.maxNs)}</td>
              <td class="ptcount">×${s.count}</td>
              <td class="ptverdict">$verdict</td>
            </tr>
            """.trimIndent()
        }
        return """
            <h2>🔧 Per-tool FPS — hangi tool'da donma var?</h2>
            <p class="cathint">
              <strong>Üst yarı</strong> (FPS satırları): yeterince uzun süren operasyonlar — kırmızı = donma, sarı = jank, yeşil = akıcı.
              <strong>Alt yarı</strong> (⚡ instant): sub-frame state mutation'lar — bunlar tek başına jank yaratamaz, median süreyi gösteriyoruz.
              Toplam <strong>${sections.size}</strong> section, <strong>${withFps.size}</strong>'i FPS-ölçülebilir, <strong>${instant.size}</strong>'i instant.
            </p>
            <table class="ptable">
              <thead>
                <tr>
                  <th></th>
                  <th class="ptname">Tool / Operation</th>
                  <th>Avg fps / median</th>
                  <th>Min</th>
                  <th>Max</th>
                  <th>Run</th>
                  <th>Verdict</th>
                </tr>
              </thead>
              <tbody>
                $fpsRows
                ${if (fpsRows.isNotBlank() && instantRows.isNotBlank()) """<tr class="ptseparator"><td colspan="7">⚡ Instant ops (sub-frame, jank yaratamaz)</td></tr>""" else ""}
                $instantRows
              </tbody>
            </table>
        """.trimIndent()
    }

    /** Minimum FPS samples required from a single section before we trust its average enough
     *  to use it as the SLA's worst-case reading. Prevents one-off glitches from dominating. */
    private const val SLA_MIN_PER_SECTION_RUNS = 2L

    /** Minimum total FPS samples (across matching sections) before we issue a PASS/FAIL verdict.
     *  Below this we show "INSUFFICIENT DATA" so the user knows to keep using the library. */
    private const val SLA_TOTAL_RUNS_FOR_VERDICT = 3L

    /**
     * Per-library FPS SLA panels. Each [PerfRegistry.SlaDeclaration] becomes a PASS/FAIL card
     * showing whether sections under that prefix met their min/target FPS budget over recent runs.
     *
     * Aggregation:
     *  - Find sections whose name starts with `prefix.` AND no longer prefix matches them
     *    (longest-prefix-wins for overlapping declarations like `imageeditor` vs `imageeditor.modular`).
     *  - Weighted average FPS (by run count) → "tipik kullanıcı deneyimi"
     *  - Worst section avg FPS → "min worst case"
     *  - Best section avg FPS → "best case"
     *  - PASS Min if worst >= minFps, PASS Target if weighted avg >= targetFps.
     */
    private fun renderSlaPanelsHtml(sections: Map<String, PerfRegistry.Stats>): String {
        val slas = PerfRegistry.slas()
        if (slas.isEmpty()) return ""

        val cards = slas.joinToString("\n") { sla ->
            // Find sections matching this SLA's prefix exclusively (no longer prefix steals them).
            val matching = sections.filter { (name, _) ->
                name.startsWith("${sla.prefix}.") && slas.none { other ->
                    other !== sla && other.prefix.length > sla.prefix.length &&
                        other.prefix.startsWith(sla.prefix) && name.startsWith("${other.prefix}.")
                }
            }
            // Require each contributing section to have collected ≥ MIN_PER_SECTION_RUNS runs
            // so a one-off noisy reading (e.g. a single 30 fps glitch on first launch) doesn't
            // dominate the worst-case calculation.
            val minPerSection = SLA_MIN_PER_SECTION_RUNS
            val totalRunThreshold = SLA_TOTAL_RUNS_FOR_VERDICT
            val withFps = matching.values.filter { it.hasFps && it.fpsCount >= minPerSection }
            val totalRuns = withFps.sumOf { it.fpsCount }
            if (totalRuns < totalRunThreshold) {
                val collected = matching.values.sumOf { it.fpsCount }
                """
                <div class="sla neutral">
                  <div class="slahead">
                    <div class="slabadge">⏳</div>
                    <div>
                      <div class="slatitle">${htmlEscape(sla.label)} <span class="slaverdict" style="background:rgba(137,144,163,.2);color:var(--muted)">INSUFFICIENT DATA</span></div>
                      <div class="slasub"><code>${sla.prefix}.*</code> · min ${"%.0f".format(sla.minFps)} / target ${"%.0f".format(sla.targetFps)} fps</div>
                    </div>
                  </div>
                  <div class="slabody">
                    Şu ana kadar $collected fps örneği toplandı; güvenilir bir verdict için en az
                    <strong>$totalRunThreshold</strong> tane lazım (her section için en az $minPerSection run).
                    Bu kütüphaneyi biraz daha kullan, panel otomatik güncellenir.
                  </div>
                </div>
                """.trimIndent()
            } else {
                val weightedAvg = withFps.sumOf { (it.fpsCount * it.fpsAverage).toDouble() } / totalRuns.toDouble()
                val worstSection = withFps.minByOrNull { it.fpsAverage }!!
                val worstAvg = worstSection.fpsAverage
                val bestAvg = withFps.maxOf { it.fpsAverage }
                val passesMin = worstAvg >= sla.minFps
                val passesTarget = weightedAvg.toFloat() >= sla.targetFps
                val cls = when {
                    !passesMin -> "bad"
                    !passesTarget -> "warn"
                    else -> "ok"
                }
                val badge = when {
                    !passesMin -> "🔴"
                    !passesTarget -> "⚠"
                    else -> "✓"
                }
                val verdict = when {
                    !passesMin -> "MIN FAIL"
                    !passesTarget -> "TARGET FAIL"
                    else -> "PASS"
                }
                val worstName = sections.entries.firstOrNull { it.value === worstSection }?.key ?: "?"
                """
                <div class="sla $cls">
                  <div class="slahead">
                    <div class="slabadge">$badge</div>
                    <div style="flex:1">
                      <div class="slatitle">${htmlEscape(sla.label)} <span class="slaverdict">$verdict</span></div>
                      <div class="slasub"><code>${sla.prefix}.*</code> · ${withFps.size} section / $totalRuns run</div>
                    </div>
                  </div>
                  <div class="slagrid">
                    <div class="slacell"><div class="slacell-label">Tipik (weighted avg)</div><div class="slacell-val">${"%.1f".format(weightedAvg)} fps</div></div>
                    <div class="slacell"><div class="slacell-label">Min (worst section)</div><div class="slacell-val ${if (passesMin) "" else "vbad"}">${"%.1f".format(worstAvg)} fps</div></div>
                    <div class="slacell"><div class="slacell-label">Max (best section)</div><div class="slacell-val">${"%.1f".format(bestAvg)} fps</div></div>
                    <div class="slacell"><div class="slacell-label">Min budget</div><div class="slacell-val small">${"%.0f".format(sla.minFps)} fps · ${if (passesMin) "✓" else "✗"}</div></div>
                    <div class="slacell"><div class="slacell-label">Target budget</div><div class="slacell-val small">${"%.0f".format(sla.targetFps)} fps · ${if (passesTarget) "✓" else "✗"}</div></div>
                  </div>
                  ${if (!passesMin) """<div class="slahint">⚠ <code>$worstName</code> ${"%.1f".format(worstAvg)} fps ile <strong>min ${"%.0f".format(sla.minFps)} budget'ini aştı</strong>. Önce burayı optimize et.</div>""" else ""}
                </div>
                """.trimIndent()
            }
        }
        return """
            <h2>🎯 Library SLA — kütüphane bazlı FPS hedefleri</h2>
            <p class="cathint">
              Her kütüphane kendi <strong>min</strong> ve <strong>target</strong> FPS'ini taahhüt eder.
              Bu paneller, o kütüphanenin section'ları altında ölçülen FPS örneklerini birleştirip
              <strong>PASS / TARGET FAIL / MIN FAIL</strong> verdict'i çıkarır. SLA tanımı için
              <code>PerfRegistry.declareSla(prefix, minFps, targetFps, label)</code> çağrısı yeter.
            </p>
            <div class="slas">$cards</div>
        """.trimIndent()
    }

    /**
     * "Frame Health" panel — top-of-FPS-section summary that mirrors what Google Play Console's
     * Android Vitals tab tracks for the last 28 days:
     *   - **Slow rendering rate** (% > 16.6 ms)  — Vitals threshold: 25%
     *   - **Frozen frame rate** (% > 700 ms)     — Vitals threshold: 0.1%
     *   - **P50/P90/P95/P99** frame duration percentiles, computed over the last 1000 frames so
     *     the tail percentiles converge.
     *
     * The verdict line up top is the user's "do I need to worry?" answer at a glance.
     */
    private fun renderFrameHealthHtml(frames: PerfRegistry.FrameStats): String {
        if (frames.totalFrames == 0L) return ""
        val slowPct = frames.slowFramePercentage
        val frozenPct = frames.frozenFramePercentage
        val verdictCls: String
        val verdictBadge: String
        val verdictTitle: String
        when {
            frozenPct > 0.5f -> {
                verdictCls = "bad"
                verdictBadge = "🔥"
                verdictTitle = "Donmalar yaygın"
            }
            slowPct > 50f -> {
                verdictCls = "bad"
                verdictBadge = "🔴"
                verdictTitle = "Frame'lerin yarısından çoğu yavaş"
            }
            frozenPct > 0.1f -> {
                verdictCls = "warn"
                verdictBadge = "⚠"
                verdictTitle = "Donma var (Play Vitals eşiği aştı: %0.1)"
            }
            slowPct > 25f -> {
                verdictCls = "warn"
                verdictBadge = "⚠"
                verdictTitle = "Slow rendering yüksek (Play Vitals eşiği: %25)"
            }
            else -> {
                verdictCls = "ok"
                verdictBadge = "✓"
                verdictTitle = "Frame health iyi"
            }
        }
        val pctClass = { v: Float, warn: Float, bad: Float -> when {
            v >= bad -> "bad"
            v >= warn -> "warn"
            else -> "ok"
        }}
        val slowClass = pctClass(slowPct, 25f, 50f)
        val frozenClass = pctClass(frozenPct, 0.1f, 0.5f)

        return """
            <h2>🏥 Frame Health — Android Vitals metrics</h2>
            <p class="cathint">
              Google Play Console'un kullandığı asıl metrikler. <strong>Slow frame</strong> = >16.6 ms (60 fps deadline'ını kaçırdı).
              <strong>Frozen frame</strong> = >700 ms (kullanıcı uygulamanın "hung" olduğunu hisseder). Play eşikleri: slow %25, frozen %0.1.
            </p>
            <div class="memverdict $verdictCls">
              <div class="memverdict-badge">$verdictBadge</div>
              <div>
                <div class="memverdict-title">$verdictTitle</div>
                <div class="memverdict-summary">
                  ${frames.totalFrames} toplam frame · slow ${"%.2f".format(slowPct)}% · frozen ${"%.3f".format(frozenPct)}%
                </div>
              </div>
            </div>

            <div class="vitals">
              <div class="vital $slowClass">
                <div class="vlabel">Slow frames</div>
                <div class="vbig">${"%.2f".format(slowPct)}%</div>
                <div class="vsub">${frames.slowFrames} / ${frames.totalFrames} frame · eşik %25</div>
              </div>
              <div class="vital $frozenClass">
                <div class="vlabel">Frozen frames</div>
                <div class="vbig">${"%.3f".format(frozenPct)}%</div>
                <div class="vsub">${frames.frozenFrames} / ${frames.totalFrames} frame · eşik %0.1</div>
              </div>
              <div class="vital">
                <div class="vlabel">Effective FPS</div>
                <div class="vbig">${"%.1f".format(frames.effectiveFps)}</div>
                <div class="vsub">son ${frames.timelineNs.size} frame'in mean'inden</div>
              </div>
            </div>

            <h3 class="vh3">Frame duration percentiles (son ${frames.percentileSamplesNs.size} frame)</h3>
            <div class="percentiles">
              <div class="pcell"><div class="plabel">P50 (median)</div><div class="pval">${formatNs(frames.p50Ns)}</div></div>
              <div class="pcell"><div class="plabel">P90</div><div class="pval">${formatNs(frames.p90Ns)}</div></div>
              <div class="pcell"><div class="plabel">P95</div><div class="pval">${formatNs(frames.p95Ns)}</div></div>
              <div class="pcell"><div class="plabel">P99</div><div class="pval">${formatNs(frames.p99Ns)}</div></div>
              <div class="pcell"><div class="plabel">Max</div><div class="pval">${formatNs(frames.maxFrameNs)}</div></div>
            </div>
            <p class="cathint" style="margin-top:6px">
              <strong>P50</strong> = tipik kullanıcı deneyimi · <strong>P95</strong> = "slowest 5%" · <strong>P99</strong> = en kötü durumlar / outliers.
              60 fps hedefinde P95 16ms'in altında kalmalı. P99 100ms'i aşıyorsa belirli senaryolarda donmalar var demektir.
            </p>
        """.trimIndent()
    }

    private fun renderMemoryVerdictHtml(): String {
        val snaps = PerfRegistry.memorySnapshot()
        val v = computeMemoryVerdict(snaps) ?: return ""
        return """
            <div class="memverdict ${v.cls}">
              <div class="memverdict-badge">${v.badge}</div>
              <div>
                <div class="memverdict-title">${v.title}</div>
                <div class="memverdict-summary">${v.summary}</div>
              </div>
            </div>
        """.trimIndent()
    }

    private fun renderGcDropsHtml(): String {
        val drops = detectGcDrops(PerfRegistry.memorySnapshot()).takeLast(8)
        if (drops.isEmpty()) {
            return """<p class="cathint">Henüz GC drop tespit edilmedi. Heap stabil kaldıkça normaldir; süre artıp da hiç drop olmuyorsa GC çalışmıyor demektir → leak göstergesi olabilir.</p>"""
        }
        val rows = drops.joinToString("\n") { (snap, drop) ->
            """<li><strong>${formatTs(snap.timestampMs)}</strong> — ${drop} MB düştü → ${snap.javaHeapUsedMb} MB</li>"""
        }
        return """
            <h2 style="margin-top:18px">🗑 GC events — son ${drops.size} drop</h2>
            <p class="cathint">Java heap'in ani düşmesi GC'nin çalıştığını gösterir; bu sağlıklı bir işarettir. Sızıntı varsa tam tersine drop olmaz.</p>
            <ul class="gcdrops">$rows</ul>
        """.trimIndent()
    }

    private fun renderMemoryThresholdLegend(): String = """
        <div class="memlegend">
          <div class="memlegend-row">
            <span class="memlegend-label">Java heap %</span>
            <span class="memlegend-band ok">≤${MemoryThresholds.JAVA_PCT_WARN}% sağlıklı</span>
            <span class="memlegend-band warn">${MemoryThresholds.JAVA_PCT_WARN}-${MemoryThresholds.JAVA_PCT_BAD}% yüksek</span>
            <span class="memlegend-band bad">>${MemoryThresholds.JAVA_PCT_BAD}% kritik</span>
          </div>
          <div class="memlegend-row">
            <span class="memlegend-label">Native heap (MB)</span>
            <span class="memlegend-band ok">≤${MemoryThresholds.NATIVE_MB_WARN} normal</span>
            <span class="memlegend-band warn">${MemoryThresholds.NATIVE_MB_WARN}-${MemoryThresholds.NATIVE_MB_BAD} yüksek</span>
            <span class="memlegend-band bad">>${MemoryThresholds.NATIVE_MB_BAD} bitmap leak şüphesi</span>
          </div>
          <div class="memlegend-row">
            <span class="memlegend-label">Toplam PSS (MB)</span>
            <span class="memlegend-band ok">≤${MemoryThresholds.PSS_MB_WARN} normal chat-app</span>
            <span class="memlegend-band warn">${MemoryThresholds.PSS_MB_WARN}-${MemoryThresholds.PSS_MB_BAD} medya işliyor</span>
            <span class="memlegend-band bad">>${MemoryThresholds.PSS_MB_BAD} OS trim bölgesi</span>
          </div>
          <div class="memlegend-row">
            <span class="memlegend-label">Trend</span>
            <span class="memlegend-band warn">${MemoryThresholds.RISING_WINDOW} ardışık örnekte ≥${MemoryThresholds.RISING_DELTA_MB}MB artış → 💧 leak şüphesi</span>
          </div>
          <div class="memlegend-row">
            <span class="memlegend-label">GC drop</span>
            <span class="memlegend-band ok">≥${MemoryThresholds.GC_DROP_MB}MB ani düşüş = sağlıklı (GC çalıştı)</span>
          </div>
        </div>
    """.trimIndent()

    private fun formatMs(ms: Long): String = when {
        ms >= 60_000 -> "%.1fdk".format(ms / 60_000.0)
        ms >= 1_000 -> "%.2fs".format(ms / 1_000.0)
        else -> "${ms}ms"
    }

    /** Format a nanosecond duration for display, picking the most readable unit. */
    private fun formatNs(ns: Long): String = when {
        ns <= 0L -> "0"
        ns < 10_000L -> "<0.01ms"
        ns < 10_000_000L -> "%.2fms".format(ns / 1_000_000.0)
        ns < 1_000_000_000L -> "%.0fms".format(ns / 1_000_000.0)
        ns < 60_000_000_000L -> "%.2fs".format(ns / 1_000_000_000.0)
        else -> "%.1fdk".format(ns / 60_000_000_000.0)
    }

    private fun renderSparkline(samplesNs: LongArray): String {
        if (samplesNs.isEmpty()) return ""
        val maxNs = (samplesNs.max()).coerceAtLeast(1L)
        val w = 100.0 / samplesNs.size
        val sb = StringBuilder("""<svg class="spark" viewBox="0 0 100 28" preserveAspectRatio="none">""")
        samplesNs.forEachIndexed { i, v ->
            val barHeight = (v.toDouble() / maxNs.toDouble() * 26.0).coerceAtLeast(1.0)
            val x = i * w
            val y = 28.0 - barHeight
            // Severity bands in ns: 200 ms = 200_000_000, 50 ms = 50_000_000.
            val color = when {
                v > 200_000_000L -> "var(--bad)"
                v > 50_000_000L -> "var(--warn)"
                else -> "var(--ok)"
            }
            sb.append("""<rect x="${"%.2f".format(x)}" y="${"%.2f".format(y)}" """)
                .append("""width="${"%.2f".format(w * 0.85)}" height="${"%.2f".format(barHeight)}" """)
                .append("""fill="$color"/>""")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun renderEventFeed(events: List<PerfRegistry.Event>): String {
        if (events.isEmpty()) return ""
        return events.take(50).joinToString(separator = "\n") { e ->
            when (e) {
                is PerfRegistry.Event.Section -> {
                    val cls = when {
                        e.durationMs > 200 -> "bad"
                        e.durationMs > 50 -> "warn"
                        else -> "ok"
                    }
                    val icon = when (cls) { "bad" -> "✗"; "warn" -> "⚠"; else -> "✓" }
                    """<div class="row $cls"><div class="ts">${formatTs(e.timestampMs)}</div><div class="icon">$icon</div><div class="label">${htmlEscape(e.name)}</div><div class="ms">${formatNs(e.durationNs)}</div></div>"""
                }
                is PerfRegistry.Event.Frame -> {
                    val isFreeze = e.durationNs >= PerfRegistry.FREEZE_THRESHOLD_NS
                    val cls = when {
                        isFreeze -> "bad"
                        e.durationMs > 100 -> "bad"
                        e.durationMs > 16 -> "warn"
                        else -> "ok"
                    }
                    val icon = if (isFreeze) "🔥" else if (cls == "bad") "✗" else "⚠"
                    val label = if (isFreeze) "DONMA" else "jank"
                    val concurrent = correlateFrameWithSection(e, events)
                    val concurrentTxt = concurrent?.let { """ <span class="sub">→ ${htmlEscape(it)}</span>""" } ?: ""
                    val stateTxt = e.uiState?.let { """ <span class="sub">${htmlEscape(it)}</span>""" } ?: ""
                    """<div class="row $cls"><div class="ts">${formatTs(e.timestampMs)}</div><div class="icon">$icon</div><div class="label">$label$stateTxt$concurrentTxt</div><div class="ms">${formatNs(e.durationNs)}</div></div>"""
                }
                is PerfRegistry.Event.MatrixIssue -> {
                    val cls = when (e.kind) {
                        "anr", "evil_method" -> "bad"
                        "leak", "io" -> "warn"
                        else -> "warn"
                    }
                    val icon = when (e.kind) {
                        "anr" -> "🔥"
                        "leak" -> "💧"
                        "io" -> "💾"
                        "evil_method" -> "⏱"
                        "fps" -> "🐢"
                        "startup" -> "🚀"
                        else -> "ℹ"
                    }
                    val label = """<strong>${htmlEscape(e.title)}</strong> <span class="sub">${htmlEscape(e.kind)}</span>"""
                    """<div class="row $cls"><div class="ts">${formatTs(e.timestampMs)}</div><div class="icon">$icon</div><div class="label">$label</div><div class="ms">Matrix</div></div>"""
                }
            }
        }
    }

    private fun formatTs(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        val zdt = instant.atZone(java.time.ZoneId.systemDefault())
        return "%02d:%02d:%02d".format(zdt.hour, zdt.minute, zdt.second)
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun jsonEscape(s: String): String = buildString(s.length + 2) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    private val HTTP_404: ByteArray = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8)
    private val HTTP_400: ByteArray = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8)
}
