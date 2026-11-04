# UI benchmarks

The UI's targets of ui's design, section 11, measured by `engine-bench` (`./gradlew :engine-bench:uiBench`): GPU times are the UI pass plus the frame start (uploads) from the graph's timestamps, at 3840 x 2160 unless noted; CPU times the code named, per frame, after warm-up. No JaCoCo, no validation.

- Date: 2026-11-04
- CPU: AMD Ryzen 9 9950X3D 16-Core Processor (32 threads)
- GPU: AMD Radeon RX 9070 XT
- OS: Windows 11 10.0; Java 27

| Target | Budget | Measured | p50 | p99 | Within |
|---|---|---|---|---|---|
| Full HUD (500 elements, 5,000 glyphs): GPU at 4K | ≤ 0.15 ms | UI pass + uploads, adaptive batching | 0.1016 ms | 0.1050 ms | yes |
| Full HUD with nothing changed: CPU | ≤ 0.05 ms | the renderer's submit (draw lists given) (the UI's own idle frame: the controls gallery below) | 0.0001 ms | 0.0001 ms | yes |
| Full HUD with 10% of elements changing: CPU | ≤ 0.3 ms | 50 elements re-recorded into the list, submitted (recording only; the UI's styles and layout of a changing HUD are ui's HudBenchmarkTest) | 0.0189 ms | 0.0253 ms | yes |
| 100,000 glyphs on screen: GPU | ≤ 0.4 ms | 4K, 500 x 200 glyphs | 0.3060 ms | 0.3273 ms | yes |
| 100,000 glyphs on screen: GPU | ≤ 0.4 ms | over a full-screen translucent backdrop | 0.4282 ms | 0.4542 ms | no |
| Line chart: 1,000,000 points streaming 1,000 a frame, 4K: CPU | ≤ 0.5 ms | a line, 3790 pixel columns; the whole UI frame | 0.5008 ms | 0.7876 ms | no |
| Line chart: 1,000,000 points streaming 1,000 a frame, 4K: GPU | ≤ 0.2 ms | a line, 3790 pixel columns; UI pass + uploads | 0.2665 ms | 0.2738 ms | no |
| Line chart: 1,000,000 points streaming 1,000 a frame, 4K: CPU | ≤ 0.5 ms | a filled area (triangles), 3790 pixel columns; the whole UI frame | 0.5612 ms | 0.8269 ms | no |
| Line chart: 1,000,000 points streaming 1,000 a frame, 4K: GPU | ≤ 0.2 ms | a filled area (triangles), 3790 pixel columns; UI pass + uploads | 0.3535 ms | 0.3628 ms | no |
| Line chart: 10,000,000 points, zoom and pan at frame rate: CPU | ≤ 1 ms | evenly spaced X, pyramid; the whole UI frame | 0.4585 ms | 0.8977 ms | yes |
| Full HUD with nothing changed: CPU | ≤ 0.05 ms | the controls gallery (about 970 elements) idle: the whole UI frame | 0.0043 ms | 0.0054 ms | yes |
| Console flood (a changing UI): CPU | ≤ 0.3 ms | 1,000 lines a frame into the open console, 1920 x 1080: the whole UI frame (lines logged before the frame; their appending is not in it) | 0.4454 ms | 0.5658 ms | no |

Not measured here (no public scene to reach from the engine): lists and tables scrolling, picking, style reload and text reshaping run as ui's own benchmark tests (`TRUESIM_BENCHMARK=true`, see ui's docs/ui.md).
