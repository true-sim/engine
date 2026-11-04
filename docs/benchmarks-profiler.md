# Profiler overhead

`./gradlew :engine-bench:profilerBench`, 2026-11-04, Windows 11, 32 hardware threads, no JaCoCo. Times of the main thread, frames back to back.

## A CPU zone (begin and end)

| | ns per zone |
|---|---|
| empty loop | 0.18 |
| profiler off | 0.34 (0.16 over the loop) |
| profiler on | 38.43 (38.25 over the loop: two `nanoTime` calls and two ring writes) |

## The frame graph's data path

The frame clock (start, two waits, submit, end) and the overlay's rings (a CPU frame and a GPU frame appended): **0.145 us per frame** (budget 10 us).

## The frame graph view

A UI (the controls gallery, idle) updated with a frame appended each time, with and without the frame graph (statistics every 0.5 s): 0.0043 ms against 0.0122 ms p50, so the view costs **7.9 us a frame** on the CPU (its lines appended to and scrolled by an offset, the rest of the chart recording nothing).

## Engine frames

Headless, the controls gallery idle at 1920 x 1080 drawn into a texture; CPU time of `engine.step()`, 3000 frames after 2000 warm ones; GPU from the frame graph's timestamps (only measured with the profiler on).

| Profiler | CPU mean | CPU p50 | CPU p99 | over off (p50) | GPU p50 |
|---|---|---|---|---|---|
| off | 0.087 ms | 0.082 ms | 0.194 ms | - | - |
| overlay, no GPU timings | 0.095 ms | 0.090 ms | 0.194 ms | +0.008 ms | - |
| overlay | 0.097 ms | 0.093 ms | 0.178 ms | +0.011 ms | 0.068 ms |
| detailed (zones, GPU, counters) | 0.211 ms | 0.198 ms | 0.322 ms | +0.117 ms | 0.162 ms |
| detailed + OS + JFR + statistics | 0.227 ms | 0.216 ms | 0.335 ms | +0.134 ms | 0.166 ms |

