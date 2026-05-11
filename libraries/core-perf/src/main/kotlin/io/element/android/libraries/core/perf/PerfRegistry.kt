/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.core.perf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight, thread-safe in-memory store of section + frame timings. Aimed at developer / debug
 * builds — use Macrobenchmark + `TraceSectionMetric` for production-grade measurements.
 *
 * **Internal precision is nanoseconds.** Sub-millisecond operations (single field assignments,
 * trivial Compose state writes) wouldn't be visible if we recorded in milliseconds. The dashboard
 * formats nanoseconds back to fractional ms / µs for display.
 *
 * Three views over the data:
 *   - [snapshot]      — aggregates per section (count/min/max/median/p95) with the last 32 raw
 *                        samples (for sparkline rendering).
 *   - [frameSnapshot] — frame timing aggregates + the last 60 frame durations (for a timeline chart).
 *   - [recentEvents]  — chronological log of the last 100 section completions and janky frames,
 *                        used to render a "what happened" feed.
 */
object PerfRegistry {
    const val WINDOW_SIZE: Int = 256
    const val SPARKLINE_SIZE: Int = 32
    const val FRAME_TIMELINE_SIZE: Int = 60
    const val EVENT_LOG_SIZE: Int = 200
    const val RECENT_JANK_SIZE: Int = 20

    /**
     * Minimum duration in nanoseconds for a section completion to be pushed to the event log.
     * Sub-millisecond state mutations (`tool.select`, `color.change`, etc.) would otherwise flood
     * the log and evict meaningful gesture/render events that the frame chart hover relies on for
     * correlation. They still appear in the per-section cards via [Stats] aggregates.
     */
    const val MIN_EVENT_LOG_DURATION_NS: Long = 1_000_000L  // 1 ms

    /** A frame slower than this is treated as a "freeze" by the verdict logic. */
    const val FREEZE_THRESHOLD_NS: Long = 500_000_000L

    /** Frame durations beyond this are "slow" per Android Vitals (Google Play Console).
     *  Anything > 16.6 ms misses the 60 fps deadline on a 60 Hz display. */
    const val SLOW_FRAME_THRESHOLD_NS: Long = 16_666_667L

    /** Frame durations beyond this are "frozen" per Android Vitals — perceived as a hang. */
    const val FROZEN_FRAME_THRESHOLD_NS: Long = 700_000_000L

    /** Buffer length used for stable percentile (P50/P90/P95/P99) computation across the recent
     *  history. ~16s at 60 Hz, ~8s at 120 Hz — enough for the percentiles to converge. */
    const val PERCENTILE_BUFFER_SIZE: Int = 1000

    /** Memory samples kept in the rolling window. At 1 Hz that's 2 minutes of history. */
    const val MEMORY_WINDOW_SIZE: Int = 120

    /** Live wall-clock FPS samples kept by [FpsSampler]. At 1 Hz that's 60 seconds. */
    const val FPS_HISTORY_SIZE: Int = 60

    private const val NS_PER_MS = 1_000_000L

    private val sections = ConcurrentHashMap<String, SectionState>()
    private val frames = FrameStatsState()
    private val events = EventLog()
    private val memory = MemoryWindow()
    private val fpsHistory = FpsHistoryWindow()
    private val slaList = CopyOnWriteArrayList<SlaDeclaration>()
    private val reporter = AtomicReference<PerfReporter>(NoopPerfReporter)

    /**
     * Record a single observation for [section]:
     * - [durationNs] in nanoseconds.
     * - [fpsDuringSection] is the framerate observed *during this gesture/op* (frames captured
     *   while it was running ÷ wall-clock duration). Pass [Float.NaN] when the section was too
     *   short to span a frame, or when frame count wasn't sampled — those entries are skipped
     *   from the per-section FPS aggregate.
     */
    fun record(section: String, durationNs: Long, fpsDuringSection: Float = Float.NaN) {
        val state = sections.computeIfAbsent(section) { SectionState() }
        state.add(durationNs, fpsDuringSection)
        // Only push *meaningful* sections to the event log so the frame chart correlation has
        // gestures and renders to anchor on. Per-section aggregates (count/median/etc.) still
        // capture every single record.
        if (durationNs >= MIN_EVENT_LOG_DURATION_NS) {
            events.push(Event.Section(System.currentTimeMillis(), section, durationNs, fpsDuringSection))
        }
        reporter.get().onSection(section, durationNs / NS_PER_MS)
    }

    /** Current cumulative frame count (since app start or last [reset]). Used by `trace { }` and
     *  [TracedGesture] to derive per-section FPS. */
    fun currentFrameCount(): Long = frames.snapshot().totalFrames

    /**
     * Record a frame measurement coming from [JankCollector] (or any custom JankStats source).
     * [durationNs] is the frame's CPU duration in nanoseconds (JankStats provides this natively);
     * [isJanky] is JankStats' own jank verdict (slow frame relative to refresh rate); [uiState] is
     * the UI state name attached to the frame, if any.
     */
    fun recordFrame(durationNs: Long, isJanky: Boolean, uiState: String?) {
        frames.add(durationNs, isJanky, uiState)
        if (isJanky || durationNs >= FREEZE_THRESHOLD_NS) {
            events.push(Event.Frame(System.currentTimeMillis(), durationNs, uiState, isJanky))
        }
    }

    /**
     * Record a Matrix-detected issue. [kind] is one of "anr", "leak", "io", "evil_method".
     * [title] is a one-line summary, [details] holds full payload (often JSON). Issues bubble up
     * to the dashboard as findings + entries in the event feed.
     */
    fun recordMatrixIssue(kind: String, title: String, details: String) {
        events.push(Event.MatrixIssue(System.currentTimeMillis(), kind, title, details))
    }

    /** Add a memory snapshot to the rolling window. Called periodically by [MemoryCollector]. */
    fun recordMemory(snapshot: MemorySnapshot) {
        memory.add(snapshot)
    }

    /** Snapshot of the recent memory samples (oldest first). */
    fun memorySnapshot(): List<MemorySnapshot> = memory.snapshot()

    /** Add a 1-second FPS sample. Called by [FpsSampler] each tick. */
    fun recordFpsSample(timestampMs: Long, fps: Float) {
        fpsHistory.add(FpsSample(timestampMs, fps))
    }

    /** Snapshot of the live FPS history (oldest first). */
    fun fpsHistory(): List<FpsSample> = fpsHistory.snapshot()

    /**
     * Declare an FPS SLA (Service-Level Agreement) for a section name [prefix]. The dashboard
     * picks up declared SLAs and shows PASS/FAIL panels per library — useful when a feature has a
     * concrete budget like "min 60 fps, target 75 fps" and you want to see at a glance whether
     * recent measurements meet it.
     *
     * Sections starting with `prefix.` (e.g. `imageeditor.modular.draw.stroke` matches prefix
     * `imageeditor.modular`) contribute their per-section FPS samples to this SLA's aggregate.
     * The longest matching prefix wins when declarations overlap.
     *
     * Call this once at app startup; declarations stay until process death (or a [resetSlas] call).
     */
    fun declareSla(prefix: String, minFps: Float = 30f, targetFps: Float = 60f, label: String = prefix) {
        slaList.add(SlaDeclaration(prefix, minFps, targetFps, label))
    }

    /** Active SLA declarations, in registration order. */
    fun slas(): List<SlaDeclaration> = slaList.toList()

    /** Wipe all declared SLAs — handy in tests, rarely needed at runtime. */
    fun resetSlas() {
        slaList.clear()
    }

    /** Snapshot of all known sections. Safe to call from any thread; the returned map is immutable. */
    fun snapshot(): Map<String, Stats> =
        sections.entries.associate { (k, v) -> k to v.stats() }

    /** Snapshot of frame stats since [reset] (or app start). */
    fun frameSnapshot(): FrameStats = frames.snapshot()

    /** Most recent completions, newest first. Includes both section completions and janky frames. */
    fun recentEvents(): List<Event> = events.snapshot()

    /** Clear all recorded samples — useful between benchmark runs in dev menus. */
    fun reset() {
        sections.clear()
        frames.reset()
        events.clear()
        memory.reset()
        fpsHistory.reset()
    }

    /**
     * Install a custom [PerfReporter] (e.g. a Sentry bridge). Pass [NoopPerfReporter] to disable.
     * Default is [NoopPerfReporter] so production builds don't pay any extra cost.
     */
    fun setReporter(newReporter: PerfReporter) {
        reporter.set(newReporter)
    }

    /**
     * All durations are stored as nanoseconds. Convenience millisecond accessors round to whole ms;
     * the dashboard renders fractional ms straight from the `*Ns` fields.
     */
    data class Stats(
        val count: Long,
        val totalNs: Long,
        val minNs: Long,
        val maxNs: Long,
        val medianNs: Long,
        val p95Ns: Long,
        /** Last [SPARKLINE_SIZE] samples in nanoseconds, oldest first. */
        val recentSamplesNs: LongArray,
        /**
         * FPS aggregates computed across the section's individual runs that lasted long enough to
         * span at least one frame. Each run contributes its own (`framesDuring × 1s ÷ duration`)
         * average. NaN means "no FPS sample yet" — section was always too short or no frames
         * were observed.
         */
        val fpsCount: Long,
        val fpsAverage: Float,
        val fpsMin: Float,
        val fpsMax: Float,
    ) {
        val averageNs: Long
            get() = if (count == 0L) 0L else totalNs / count

        val hasFps: Boolean get() = fpsCount > 0L && !fpsAverage.isNaN()

        // Whole-ms convenience accessors (rounded). Use *Ns + formatNs for sub-ms precision.
        val totalMs: Long get() = totalNs / NS_PER_MS
        val minMs: Long get() = minNs / NS_PER_MS
        val maxMs: Long get() = maxNs / NS_PER_MS
        val medianMs: Long get() = medianNs / NS_PER_MS
        val p95Ms: Long get() = p95Ns / NS_PER_MS
        val averageMs: Long get() = averageNs / NS_PER_MS

        // Identity equality — Stats is read-once snapshot, no need for value equality on the array.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class FrameStats(
        val totalFrames: Long,
        val jankyFrames: Long,
        val maxFrameNs: Long,
        val freezes: Long,
        val recentJank: List<RecentJank>,
        /** Last [FRAME_TIMELINE_SIZE] frame durations in nanoseconds (oldest first). */
        val timelineNs: LongArray,
        /** Wall-clock timestamps (ms epoch) for each entry of [timelineNs]; same length, same order. */
        val timelineTimestampsMs: LongArray,
        /** Cumulative counters for Android Vitals categories. */
        val slowFrames: Long,
        val frozenFrames: Long,
        /** Sorted snapshot of the recent [PERCENTILE_BUFFER_SIZE] frame durations, used for
         *  stable percentile computation. Length is min(totalFrames, PERCENTILE_BUFFER_SIZE). */
        val percentileSamplesNs: LongArray,
    ) {
        val maxFrameMs: Long get() = maxFrameNs / NS_PER_MS

        val jankPercentage: Float
            get() = if (totalFrames == 0L) 0f else (jankyFrames.toFloat() * 100f) / totalFrames.toFloat()

        /** % of frames that exceeded 16.6ms (60 fps deadline) — Android Vitals "slow rendering". */
        val slowFramePercentage: Float
            get() = if (totalFrames == 0L) 0f else (slowFrames.toFloat() * 100f) / totalFrames.toFloat()

        /** % of frames that exceeded 700ms — Android Vitals "frozen frames". */
        val frozenFramePercentage: Float
            get() = if (totalFrames == 0L) 0f else (frozenFrames.toFloat() * 100f) / totalFrames.toFloat()

        /**
         * Effective FPS implied by the recent timeline (1 / mean frame duration). This is "if
         * frames keep arriving at this duration, the device would render X fps". Not wall-clock
         * FPS — for that we'd need arrival timestamps, which JankStats doesn't expose.
         */
        val effectiveFps: Float
            get() {
                if (timelineNs.isEmpty()) return 0f
                val mean = timelineNs.average()
                if (mean <= 0) return 0f
                return (1_000_000_000.0 / mean).toFloat()
            }

        // Percentiles over the last PERCENTILE_BUFFER_SIZE frames. Returns 0 when no samples yet.
        val p50Ns: Long get() = percentileAt(50)
        val p90Ns: Long get() = percentileAt(90)
        val p95Ns: Long get() = percentileAt(95)
        val p99Ns: Long get() = percentileAt(99)

        private fun percentileAt(percentile: Int): Long {
            if (percentileSamplesNs.isEmpty()) return 0L
            val idx = ((percentileSamplesNs.size - 1) * percentile) / 100
            return percentileSamplesNs[idx]
        }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Memory snapshot collected by [MemoryCollector]. Sizes are in MiB to stay readable in the
     * dashboard; nanosecond precision isn't needed for memory.
     */
    data class MemorySnapshot(
        val timestampMs: Long,
        val javaHeapUsedMb: Long,
        val javaHeapMaxMb: Long,
        val nativeHeapAllocatedMb: Long,
        val totalPssMb: Long,
    ) {
        val javaHeapUsagePercent: Int
            get() = if (javaHeapMaxMb == 0L) 0 else ((javaHeapUsedMb * 100) / javaHeapMaxMb).toInt()
    }

    data class RecentJank(
        val durationNs: Long,
        val uiState: String?,
    ) {
        val durationMs: Long get() = durationNs / NS_PER_MS
    }

    /**
     * Wall-clock FPS sample produced by [FpsSampler]. Each sample = frames captured between
     * `t-1s` and `t`. Idle screens (no frames being scheduled) report low/zero FPS — that's
     * the actual rendering rate, not a bug.
     */
    data class FpsSample(
        val timestampMs: Long,
        val fps: Float,
    )

    /**
     * FPS SLA budget declared by a library/feature via [declareSla]. The dashboard renders a
     * PASS/FAIL panel per declaration; sections under [prefix] feed their per-run FPS samples in.
     */
    data class SlaDeclaration(
        val prefix: String,
        val minFps: Float,
        val targetFps: Float,
        val label: String,
    )

    sealed interface Event {
        val timestampMs: Long

        data class Section(
            override val timestampMs: Long,
            val name: String,
            val durationNs: Long,
            /** FPS observed during this specific run; [Float.NaN] when sub-frame. */
            val fpsDuringSection: Float = Float.NaN,
        ) : Event {
            val durationMs: Long get() = durationNs / NS_PER_MS
            val hasFps: Boolean get() = !fpsDuringSection.isNaN() && fpsDuringSection > 0f
        }

        data class Frame(
            override val timestampMs: Long,
            val durationNs: Long,
            val uiState: String?,
            val isJanky: Boolean,
        ) : Event {
            val durationMs: Long get() = durationNs / NS_PER_MS
        }

        /**
         * An issue surfaced by Tencent Matrix (ANR, activity leak, I/O issue, slow method, …).
         */
        data class MatrixIssue(
            override val timestampMs: Long,
            val kind: String,   // anr | leak | io | evil_method | fps | startup | other
            val title: String,
            val details: String,
        ) : Event
    }

    private class SectionState {
        private val lock = Any()
        private var count = 0L
        private var totalNs = 0L
        private var minNs = Long.MAX_VALUE
        private var maxNs = Long.MIN_VALUE
        private val window = LongArray(WINDOW_SIZE)
        private var windowSize = 0
        private var windowHead = 0

        // FPS aggregates — separate counters because not every run records FPS (sub-frame ops).
        private var fpsCount = 0L
        private var fpsSum = 0.0
        private var fpsMin = Float.POSITIVE_INFINITY
        private var fpsMax = Float.NEGATIVE_INFINITY

        fun add(durationNs: Long, fpsDuringSection: Float) = synchronized(lock) {
            count += 1
            totalNs += durationNs
            if (durationNs < minNs) minNs = durationNs
            if (durationNs > maxNs) maxNs = durationNs
            window[windowHead] = durationNs
            windowHead = (windowHead + 1) % WINDOW_SIZE
            if (windowSize < WINDOW_SIZE) windowSize += 1

            if (!fpsDuringSection.isNaN() && fpsDuringSection > 0f) {
                fpsCount += 1
                fpsSum += fpsDuringSection
                if (fpsDuringSection < fpsMin) fpsMin = fpsDuringSection
                if (fpsDuringSection > fpsMax) fpsMax = fpsDuringSection
            }
        }

        fun stats(): Stats = synchronized(lock) {
            if (count == 0L) {
                return@synchronized Stats(
                    count = 0L, totalNs = 0L, minNs = 0L, maxNs = 0L,
                    medianNs = 0L, p95Ns = 0L, recentSamplesNs = LongArray(0),
                    fpsCount = 0L, fpsAverage = Float.NaN, fpsMin = Float.NaN, fpsMax = Float.NaN,
                )
            }
            val sorted = LongArray(windowSize)
            System.arraycopy(window, 0, sorted, 0, windowSize)
            sorted.sort()
            val median = sorted[windowSize / 2]
            val p95Index = ((windowSize - 1) * 95) / 100
            val p95 = sorted[p95Index]

            // Build oldest-first slice of the last SPARKLINE_SIZE samples for the chart.
            val sparkSize = minOf(windowSize, SPARKLINE_SIZE)
            val sparkline = LongArray(sparkSize)
            for (i in 0 until sparkSize) {
                val idx = (windowHead - sparkSize + i + WINDOW_SIZE) % WINDOW_SIZE
                sparkline[i] = window[idx]
            }

            val fpsAvg = if (fpsCount > 0L) (fpsSum / fpsCount).toFloat() else Float.NaN
            val fMin = if (fpsCount > 0L) fpsMin else Float.NaN
            val fMax = if (fpsCount > 0L) fpsMax else Float.NaN

            Stats(
                count = count,
                totalNs = totalNs,
                minNs = minNs,
                maxNs = maxNs,
                medianNs = median,
                p95Ns = p95,
                recentSamplesNs = sparkline,
                fpsCount = fpsCount,
                fpsAverage = fpsAvg,
                fpsMin = fMin,
                fpsMax = fMax,
            )
        }
    }

    private class FrameStatsState {
        private val lock = Any()
        private var totalFrames = 0L
        private var jankyFrames = 0L
        private var slowFrames = 0L
        private var frozenFrames = 0L
        private var freezes = 0L
        private var maxFrameNs = 0L
        private val recentJank = ArrayDeque<RecentJank>(RECENT_JANK_SIZE)
        private val timeline = LongArray(FRAME_TIMELINE_SIZE)
        private val timelineTimestamps = LongArray(FRAME_TIMELINE_SIZE)
        private var timelineSize = 0
        private var timelineHead = 0
        // Larger ring buffer for stable percentile computation.
        private val percentileBuffer = LongArray(PERCENTILE_BUFFER_SIZE)
        private var percentileBufferSize = 0
        private var percentileBufferHead = 0

        fun add(durationNs: Long, isJanky: Boolean, uiState: String?) = synchronized(lock) {
            totalFrames += 1
            if (durationNs > maxFrameNs) maxFrameNs = durationNs
            if (durationNs >= FREEZE_THRESHOLD_NS) freezes += 1
            // Android Vitals categories — independent of JankStats' isJank verdict (which is
            // refresh-rate-aware) so we can correlate with Play Console numbers directly.
            if (durationNs > SLOW_FRAME_THRESHOLD_NS) slowFrames += 1
            if (durationNs > FROZEN_FRAME_THRESHOLD_NS) frozenFrames += 1
            if (isJanky) {
                jankyFrames += 1
                if (recentJank.size == RECENT_JANK_SIZE) recentJank.removeFirst()
                recentJank.addLast(RecentJank(durationNs, uiState))
            }
            timeline[timelineHead] = durationNs
            timelineTimestamps[timelineHead] = System.currentTimeMillis()
            timelineHead = (timelineHead + 1) % FRAME_TIMELINE_SIZE
            if (timelineSize < FRAME_TIMELINE_SIZE) timelineSize += 1
            percentileBuffer[percentileBufferHead] = durationNs
            percentileBufferHead = (percentileBufferHead + 1) % PERCENTILE_BUFFER_SIZE
            if (percentileBufferSize < PERCENTILE_BUFFER_SIZE) percentileBufferSize += 1
        }

        fun snapshot(): FrameStats = synchronized(lock) {
            val tl = LongArray(timelineSize)
            val ts = LongArray(timelineSize)
            for (i in 0 until timelineSize) {
                val idx = (timelineHead - timelineSize + i + FRAME_TIMELINE_SIZE) % FRAME_TIMELINE_SIZE
                tl[i] = timeline[idx]
                ts[i] = timelineTimestamps[idx]
            }
            // Sorted percentile snapshot — copy + sort once per snapshot, ~5µs at 1000 entries.
            val sorted = LongArray(percentileBufferSize)
            System.arraycopy(percentileBuffer, 0, sorted, 0, percentileBufferSize)
            sorted.sort()
            FrameStats(
                totalFrames = totalFrames,
                jankyFrames = jankyFrames,
                maxFrameNs = maxFrameNs,
                freezes = freezes,
                recentJank = recentJank.toList(),
                timelineNs = tl,
                timelineTimestampsMs = ts,
                slowFrames = slowFrames,
                frozenFrames = frozenFrames,
                percentileSamplesNs = sorted,
            )
        }

        fun reset() = synchronized(lock) {
            totalFrames = 0L
            jankyFrames = 0L
            slowFrames = 0L
            frozenFrames = 0L
            freezes = 0L
            maxFrameNs = 0L
            recentJank.clear()
            timelineSize = 0
            timelineHead = 0
            percentileBufferSize = 0
            percentileBufferHead = 0
        }
    }

    private class MemoryWindow {
        private val lock = Any()
        private val deque = ArrayDeque<MemorySnapshot>(MEMORY_WINDOW_SIZE)

        fun add(snapshot: MemorySnapshot) = synchronized(lock) {
            if (deque.size == MEMORY_WINDOW_SIZE) deque.removeFirst()
            deque.addLast(snapshot)
        }

        fun snapshot(): List<MemorySnapshot> = synchronized(lock) { deque.toList() }

        fun reset() = synchronized(lock) { deque.clear() }
    }

    private class FpsHistoryWindow {
        private val lock = Any()
        private val deque = ArrayDeque<FpsSample>(FPS_HISTORY_SIZE)

        fun add(sample: FpsSample) = synchronized(lock) {
            if (deque.size == FPS_HISTORY_SIZE) deque.removeFirst()
            deque.addLast(sample)
        }

        fun snapshot(): List<FpsSample> = synchronized(lock) { deque.toList() }

        fun reset() = synchronized(lock) { deque.clear() }
    }

    private class EventLog {
        private val lock = Any()
        private val deque = ArrayDeque<Event>(EVENT_LOG_SIZE)

        fun push(e: Event) = synchronized(lock) {
            if (deque.size == EVENT_LOG_SIZE) deque.removeFirst()
            deque.addLast(e)
        }

        /** Returns events newest-first. */
        fun snapshot(): List<Event> = synchronized(lock) {
            val out = ArrayList<Event>(deque.size)
            for (i in deque.indices.reversed()) out.add(deque[i])
            out
        }

        fun clear() = synchronized(lock) { deque.clear() }
    }
}
