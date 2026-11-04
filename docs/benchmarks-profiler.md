# Profiler overhead

`./gradlew :engine-bench:profilerBench`, 2026-11-04, Windows 11, 32 hardware threads, no JaCoCo. Times of the main thread, frames back to back.

## A CPU zone (begin and end)

| | ns per zone |
|---|---|
| empty loop | 0.18 |
| profiler off | 0.34 (0.16 over the loop) |
| profiler on | 38.59 (38.41 over the loop: two `nanoTime` calls and two ring writes) |

## The frame graph's data path

The frame clock (start, two waits, submit, end) and the overlay's rings (a CPU frame and a GPU frame appended): **0.144 us per frame** (budget 10 us).

## The frame graph view

A UI (the controls gallery, idle) updated with a frame appended each time, with and without the frame graph (statistics every 0.5 s): 0.0044 ms against 0.0492 ms p50, so the view costs **44.8 us a frame** on the CPU (its chart's plot recorded again each frame).

## Engine frames

Headless, the controls gallery idle at 1920 x 1080 drawn into a texture; CPU time of `engine.step()`, 3000 frames after 2000 warm ones; GPU from the frame graph's timestamps (only measured with the profiler on).

| Profiler | CPU mean | CPU p50 | CPU p99 | over off (p50) | GPU p50 |
|---|---|---|---|---|---|
| off | 0.084 ms | 0.080 ms | 0.179 ms | - | - |
| overlay, no GPU timings | 0.112 ms | 0.106 ms | 0.207 ms | +0.025 ms | - |
| overlay | 0.127 ms | 0.116 ms | 0.217 ms | +0.036 ms | 0.089 ms |
| detailed (zones, GPU, counters) | 0.256 ms | 0.245 ms | 0.394 ms | +0.165 ms | 0.192 ms |
| detailed + OS + JFR + statistics | 0.240 ms | 0.220 ms | 0.369 ms | +0.140 ms | 0.176 ms |

