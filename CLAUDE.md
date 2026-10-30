# Working on the engine

- Read `docs/pitfalls.md` before changing per-frame code, the frame graph, shaders or benchmarks: it lists mistakes
  made here once, with their evidence and the rule that follows.
- Add to it when a bug, a regression or a wrong assumption costs real time. Only verified things: a measurement, a
  reproduced failure, a test that fails without the fix, or code that shows the fault, with a link to the proof. A
  cause not found is written as an observation, never as a guess.
