# Geode Android Launcher

Launches a vanilla copy of Geometry Dash with the Geode loader added.

## Shortcut Links

The launcher supports a handful of links as an escape hatch when the main functionality is impaired in some way (such as needing to prevent an automatic launch due to a crash).  
You can open these links by copying them into the URL bar in some browsers (like Chrome), or through ADB.

| Link                                        | Action                                                                   |
|---------------------------------------------|--------------------------------------------------------------------------|
| geode-launcher://main                       | Opens to the main activity, forces the user to explicitly confirm launch |
| geode-launcher://main/launch                | Opens to the game after the launcher is finished checking for updates    | 
| geode-launcher://main/launch?safe-mode=true | Opens to the game like before, but with safe mode enabled                |
| geode-launcher://settings                   | Opens the main settings                                                  |
| geode-launcher://developer-settings         | Opens the developer settings                                             |
| geode-launcher://logs                       | Opens the application logs                                               |
| geode-launcher://crashes                    | Opens the crash dumps viewer                                             |

## Launcher Diagnostics (alpha)

The `launcher-diagnostics` branch adds a launcher-owned JSONL session recorder, enabled by default and configurable from Developer options.

- Device header: Android build, ABI, CPU count, display dimensions and refresh rate, and system RAM.
- Process samples (once per second): process CPU time converted to one-core percentage, PSS, Dalvik/native PSS, native and Java heap, system available RAM, thread count, and Android thermal status where supported.
- Cocos renderer windows: callback cadence, callback wall time, GL-thread CPU time, p50/p95/max, and intervals exceeding 1.5x the display refresh budget.
- Level segments: when DashCore 0.11.2-alpha is installed, PlayLayer start/end boundaries split level rendering metrics from menu rendering.
- DashCore capture summary: the per-level event includes object traversal, sprite candidates/accepted/rejected, texture counts, snapshot capacity, and capture/update timing counters.
- Graphics context: GL vendor, renderer, version, shading language version and a truncated extensions string.
- Lifecycle events: activity pause/resume/focus, surface changes, session start/end.
- Sessions are stored under the launcher's private files directory at `diagnostics/sessions`, with bounded retention, and can be exported from Developer options → Launcher Diagnostics.

### Render timing boundaries

Frame intervals are measured between calls to `Cocos2dxRenderer.onDrawFrame`. Renderer callback wall time and GL-thread CPU time are measured around the original Cocos callback. These are CPU-side measurements: they do **not** measure GPU execution time or guarantee that a frame was presented.

The launcher records all Cocos scenes by default. A level-only segment is recorded when a compatible Geode integration calls `LauncherDiagnostics.onLevelStarted(String, String)` and `LauncherDiagnostics.onLevelEnded()`. DashCore 0.11.2-alpha also forwards its per-level capture summary through `LauncherDiagnostics.onDashCoreLevelDiagnostics(String)`. Those counters describe DashCore's read-only object-capture pass; they are not GPU draw-call counts. Without the lifecycle markers, the `level_renderer` window remains empty; this avoids incorrectly labeling menu frames as level rendering.

No FPS limits, clock controls, thermal governors, gameplay hooks or rendering changes are introduced by this diagnostic branch.
