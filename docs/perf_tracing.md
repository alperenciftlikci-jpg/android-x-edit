# Performance tracing

Element X has a tiny tracing layer (`:libraries:core-perf`) plus a Macrobenchmark module (`:tests:macrobenchmark`) so you can measure and compare modules side-by-side. The pieces are:

- `trace { }` / `traceAsync { }` — wraps `androidx.tracing.Trace` so sections show up in Perfetto / Studio System Trace, *and* records duration into an in-memory `PerfRegistry` for dev menus.
- `TraceSectionMetric` — Macrobenchmark reads the same section names and produces JSON with median/p95 latency, plus a `*.perfetto-trace` you can open in https://ui.perfetto.dev.

## Section naming convention

Use `<module>.<operation>[.<step>]` — e.g. `imageeditor.export`, `imageeditor.export.draw`. The convention matters because it lets *alternative* implementations of the same operation share section names: if a future `:libraries:imageeditor-fancy` also emits `imageeditor.export`, the same Macrobenchmark test compares them automatically.

## Adding tracing to a new module

1. **Depend on core-perf** in your module's `build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(projects.libraries.corePerf)
       // ...
   }
   ```

2. **Wrap the public operations** that you want to measure. Most modules need 1 root section + 2-4 sub-sections.
   ```kotlin
   import io.element.android.libraries.core.perf.trace
   import io.element.android.libraries.core.perf.traceAsync

   suspend fun export(...): Result<Uri> = traceAsync("mymodule.export") {
       val decoded = trace("mymodule.export.decode") { loadBitmap(...) }
       trace("mymodule.export.encode") { encode(decoded) }
   }
   ```
   - Use `traceAsync` for `suspend` blocks and concurrent invocations, `trace` for synchronous code.
   - Pre-release builds run these zero-cost (`Trace.beginSection` is cheap; `PerfRegistry.record` is a single map lookup + atomic update).

3. **Add a Macrobenchmark test** in `tests/macrobenchmark/src/main/kotlin/...`:
   ```kotlin
   @Test
   fun export() = rule.measureRepeated(
       packageName = "io.element.android.x",
       metrics = listOf(
           TraceSectionMetric("mymodule.export"),
           TraceSectionMetric("mymodule.export.decode"),
           FrameTimingMetric(),
       ),
       iterations = 5,
       startupMode = StartupMode.WARM,
   ) {
       startActivityAndWait()
       // UIAutomator: drive the UI flow that triggers `export()`.
   }
   ```

## Live HUD: viewing the report from your laptop

Debug builds run a tiny HTTP server on `127.0.0.1:9999` that exposes:
- our own `PerfRegistry` snapshot (every `trace { ... }` section)
- JankStats frame timing
- **Tencent Matrix** auto-detected issues: ANR, Activity leaks, slow/leaked I/O, low FPS, slow startup
- An **interactive uPlot chart** that polls `/perf.json` every 2s

Tencent Matrix is loaded as `debugImplementation` only — the release variant of `:libraries:core-perf` ships a no-op `MatrixBridge` stub, so production APKs do not pull in Matrix or its native libraries. See [MatrixBridge.kt](../libraries/core-perf/src/debug/kotlin/io/element/android/libraries/core/perf/MatrixBridge.kt) for the integration.

Setup:

```bash
# 1. Install the debug build on the device (USB or Wi-Fi adb)
./gradlew :app:installDebug

# 2. Forward the device port to your laptop's localhost
adb forward tcp:9999 tcp:9999

# 3. Open the report in your browser
start http://localhost:9999          # Windows
# or:    open http://localhost:9999  # macOS
# or:    xdg-open http://localhost:9999  # Linux
```

The page auto-refreshes every 2 seconds. Tab while you use the app — drive the feature you want to measure (e.g. open the image editor, edit, export), then tab back to the browser.

What you see:
- **Verdict banner** — traffic light: ✓ smooth / ⚠ jank / 🔥 freeze, plus a one-line summary.
- **Otomatik tanı** — auto-generated findings: which child dominates a parent op, where freezes occurred (correlated to concurrent section), high variance ops, jank concentration per UI state, slow ops, and any Matrix-detected issues bubbled up as findings.
- **Waterfall** — for compound ops (e.g. `imageeditor.export` with `.decode`/`.transform`/`.draw`/`.crop`/`.encode`), each child's median as a percentage of the parent. Bottleneck child shown in red.
- **uPlot frame chart** — interactive, hover for tooltip, auto-refreshes every 2s.
- **Section cards** — sorted by total impact (count × median). Each card has: big median number, sparkline, 10-bucket histogram, σ + cv (variance metrics), p95/max/min, total time, app-wide impact.
- **Olay akışı** — chronological feed: section completions, janky frames + concurrent section ("DONMA → imageeditor.export.draw"), Matrix issues with kind icon (🔥 ANR, 💧 leak, 💾 IO).
- **Reset** button — clears all registries; the page redirects back after.

JSON view (for tooling / future MCP wrapper): `http://localhost:9999/perf.json`.

The server is **debug-only** (gated by `BuildConfig.DEBUG` in `ElementXApplication.kt`) and binds to loopback only — release APKs don't ship the server at all.

## Running it

```bash
# unit tests for the registry / trace wrapper
./gradlew :libraries:core-perf:test

# build the macrobenchmark test (needs a connected device for the actual run)
./gradlew :tests:macrobenchmark:assembleBenchmark

# run on a connected physical device (emulator results aren't representative)
./gradlew :tests:macrobenchmark:connectedBenchmarkAndroidTest
```

Outputs land in `tests/macrobenchmark/build/outputs/connected_android_test_additional_output/`:
- `*.json` — median / p95 / min / max for each metric.
- `*.perfetto-trace` — drag into https://ui.perfetto.dev to inspect the flame graph (your custom sections appear as their own tracks).

## Comparing implementations

If you have two modules implementing the same operation (`:libraries:imageeditor` vs. a third-party library wrapper), keep the **same section names** in both. The single Macrobenchmark test then yields directly comparable numbers — switch the module wired into `:app` and re-run.

## What Tencent Matrix detects automatically

Without you adding any `trace { }` calls, Matrix watches:
- **ANR** (high-accuracy stall detection with stack trace) → 🔥 finding + entry in olay akışı
- **Activity leaks** (referent retained after `finish()`) → 💧 finding with retention path
- **I/O issues** — slow read on main thread, unclosed `Closeable` (e.g. `InputStream` leaks), repeat-read of the same file, small-buffer read → 💾 finding with file path + duration
- **FPS** (parallel to JankStats but Matrix's own implementation) → low-FPS reports
- **Slow startup** (cold/warm/hot) → 🚀 finding

Matrix's "EvilMethod" tracer (per-method bytecode-level timing) is **off by default** because it requires the `matrix-gradle-plugin` to inject timing instructions into every method, which slows debug builds significantly. If you ever want it: see [Tencent/matrix wiki — TraceCanary](https://github.com/Tencent/matrix/wiki/Matrix-Android-TraceCanary).

## Forwarding to Sentry / Datadog (optional, future)

`PerfRegistry` accepts a `PerfReporter` plug. The default is a no-op. To forward production samples, install a custom reporter at app startup:

```kotlin
PerfRegistry.setReporter { name, durationMs ->
    if (durationMs > 200) Sentry.addBreadcrumb(/* ... */)
}
```

Macrobenchmark stays the source of truth for deterministic numbers; Sentry is only for "is this regressing in production?" sampling.

## Libraries we depend on

| Library | Role |
|---|---|
| `androidx.tracing:tracing-ktx` 1.3.0 | Wraps `Trace.beginSection` so our sections show up in Studio System Trace + Perfetto |
| `androidx.metrics:metrics-performance` (JankStats) 1.0.0-beta02 | Frame-level jank detection per Activity window |
| `androidx.benchmark:benchmark-macro-junit4` 1.4.1 | `MacrobenchmarkRule` + `TraceSectionMetric` for deterministic CI measurements |
| `com.tencent.matrix:*` 2.1.0 | TraceCanary (ANR/FPS/startup), ResourceCanary (Activity leaks), IOCanary (I/O issues). **Debug only.** |
| `uPlot` 1.6.30 (bundled JS, 48 KB) | Interactive frame timing chart in the dashboard |

### Image-editor A/B comparison libraries

`:libraries:imageeditor-modular` is a parallel implementation of `:libraries:imageeditor` that swaps in third-party libraries for the heavy tools, so we can compare their performance side-by-side on the dashboard:

| Tool | Baseline (`imageeditor.*`) | Modular (`imageeditor.modular.*`) |
|---|---|---|
| Crop | own Compose Canvas overlay | `com.github.yalantis:ucrop:2.2.11` (`UCropView` via `AndroidView`) |
| Draw | own Compose Canvas + Path/Paint | `androidx.ink:1.0.0` (`InProgressStrokes` Compose + `CanvasStrokeRenderer`) |
| Text | own Compose overlay | own Compose overlay (identical) |
| Filter | none | none |

Both modules share the same `<module>.<op>` trace naming convention, with a `.modular.` infix in the alternative implementation. So `imageeditor.export.draw` (baseline) and `imageeditor.modular.export.draw` (modular) appear alphabetically adjacent on the dashboard, making the diff at a glance.

To switch implementations at runtime, use the small "M" button next to the Edit pencil in the attachment preview screen.

## Caveats

- Macrobenchmark requires `minSdk >= 28` and a non-debuggable, profileable app variant. We enabled `isProfileable = true` on the `release` build type for this reason — see `app/build.gradle.kts`.
- Emulator numbers are noisy. Use a physical device for any decision-grade measurement.
- The default `ImageEditorBenchmark` only does `startActivityAndWait()` until a deeplink to the editor exists — drive the actual export flow once you wire one up.
- Matrix init is wrapped in a `try/catch` (see `MatrixBridge.kt`). If it ever fails (e.g. native lib mismatch on a new ABI), the rest of the perf stack still works.
- The `:libraries:core-perf` debug AAR is heavier than its release counterpart (~5 MB vs ~50 KB) because Matrix bundles native `.so` files for armeabi-v7a / arm64-v8a / x86 / x86_64. This only affects debug builds.
