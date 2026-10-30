# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened in this repository. It says what went wrong, how that was established,
the rule that follows, and where the proof is (a PR, a test, a measurement). Only verified things go here: a
measurement, a reproduced failure, a test that fails without the fix, or a reading of the code that shows the fault.
Guesses, hunches and "probably" stay out. When the cause of a failure is unknown, the entry says so and records only
what was observed.

Add an entry when a bug, a regression or a wrong assumption costs real time. Keep it short, and link the evidence.

## Per-frame code and allocation

**LWJGL Vulkan calls allocate per call.** LWJGL wraps a function's address in a `MemorySegment` on every call (32
bytes). The JIT removes that only where it inlines the whole call, and how much it inlines changes with unrelated code.
- Seen twice: the renderer's frames began allocating 32 bytes after an unrelated change (found with JFR,
  [#133](https://github.com/true-sim/engine/pull/133)); later `FrameGraph.beginFrame` allocated 64 bytes per frame in
  the scene benchmark, from two `vkResetCommandPool` calls (measured per frame stage with
  `getCurrentThreadAllocatedBytes`, [#141](https://github.com/true-sim/engine/pull/141)).
- Rule: every call made each frame goes through `Commands` (engine-vulkan), which keeps each function's segment. A new
  per-frame Vulkan call gets a `Commands` entry first.

**A passing allocation test does not prove a path allocates nothing.** The graph's own allocation tests passed while
the same `beginFrame` allocated in the scene benchmark: in the test, the JIT happened to inline the call.
- Proof: [#141](https://github.com/true-sim/engine/pull/141).
- Rule: also read the scene benchmark's "bytes allocated" column; when it is not 0, measure each stage of the frame to
  find the call.

**`Enum.values()` copies its array on every call.** `Format.of` allocated through it.
- Proof: [#132](https://github.com/true-sim/engine/pull/132) (`Format.ALL`).
- Rule: keep `values()` in a static field.

## Frame graph

**A write is visible to nothing until a barrier.** The compiler once marked a write's own stage and access visible, so
a storage write followed by a read in the same stage got no barrier (the depth pyramid read zeros).
- Proof: [#133](https://github.com/true-sim/engine/pull/133).
- Rule: after a write, visibility is empty; only a barrier makes it visible.

**An address of a `FrameBuffer` names the copy of the slot current when it was taken.** The renderer wrote each view's
`deviceAddress()` once into a table, so every frame slot's table pointed at the copy of the slot the view was made in;
frames of other slots read a copy the CPU rewrote while they were in flight.
- Proof: a reading of `FrameBuffer.deviceAddress()` and `MeshletRenderer.createView`; fixed in
  [#138](https://github.com/true-sim/engine/pull/138) (views in one per-slot array, indexed).
- Rule: never store a `FrameBuffer`'s address in GPU data once; index into the frame's own copy, or write the address
  each frame.

## Shaders and the GPU

**An instance's `mesh` word holds flag bits.** Indexing the mesh table with it unmasked read out of bounds and lost the
device.
- Proof: reproduced in [#144](https://github.com/true-sim/engine/pull/144) (every renderer test lost the device);
  fixed by masking.
- Rule: index meshes with `instance.mesh & INSTANCE_MESH`, or with an instance from `relative()`, which masks.

**Mesh shader outputs are expensive on RDNA.** Widening `VertexOut` from a normal and a meshlet number to about 20
values made the mesh shader path twice as slow as the indexed one on an RX 9070 XT. The cost is in the fragments
reading the values: with a trivial fragment shader the gap mostly closed, and a fragment shader that only reads every
value made it larger.
- Proof: the experiments in [#144](https://github.com/true-sim/engine/pull/144), with `MeshletBenchmark`.
- Rule: after changing what vertices carry, run `MeshletBenchmark` and compare both paths in the same session.

**Slang does not mark per-primitive fragment inputs.** A fragment shader input fed by a mesh shader's per-primitive
output compiled without the `PerPrimitiveEXT` decoration (Slang 2026.20, checked in the SPIR-V).
- Proof: `slangc -target spirv-asm`, in [#144](https://github.com/true-sim/engine/pull/144).
- Rule: pass per-triangle values through the `SV_PrimitiveID` built-in, as `lean.slang` does.

**Observed, cause unknown: depth-only mesh pipelines whose fragment shader reads `SV_PrimitiveID` and
`SV_Barycentrics`.** On an RX 9070 XT, such a pipeline for the masked bin's shadow map lost the device in
`ShadowsTest.maskedTexelsCastNoShadow`, every run; the same reads with color attachments did not.
- Proof: reproduced, and gone with the full-output pipeline, in [#144](https://github.com/true-sim/engine/pull/144).
- Rule: the masked bin's depth uses `meshIdsMain` with draw.slang's `maskedDepthMain`.

**Features and sharing must match the device.** Cube array image views failed validation until `imageCubeArray` was
enabled, and on lavapipe (one queue family for graphics and compute) images failed when they listed that family twice
for concurrent sharing.
- Proof: [#132](https://github.com/true-sim/engine/pull/132); the device enables `imageCubeArray` where present, and
  `GpuImage` lists each family once.
- Rule: run the tests with validation (they fail on any validation error), on lavapipe too.

## Tests and measurements

**After a device loss, look at the first failure only.** Once one test lost the device, the tests after it failed too,
with zeros and empty images that pointed at unrelated code.
- Proof: [#144](https://github.com/true-sim/engine/pull/144), where 13 renderer tests failed from one bad index.
- Rule: find the first test reporting `VK_ERROR_DEVICE_LOST`, fix that, then rerun.

**Benchmark numbers in the README go stale.** The per-path table stayed from the first mesh shader benchmark while
materials and lighting doubled the mesh path's time.
- Proof: [#143](https://github.com/true-sim/engine/issues/143).
- Rule: a change to shared shading or vertex data reruns `MeshletBenchmark` and updates the tables, with the date.

**Known flaky test:** `SceneSyncTest.aClearCoatInTheDataTurnsTheFeatureOn` fails about one run in four on main.
- Proof: reproduced on main, [#142](https://github.com/true-sim/engine/issues/142).
- Rule: until it is fixed, rerun it before suspecting a change.

## Java

**A record's components are its accessors.** A method named like a component must return the component's type:
`GraphicsProgram.doubleSided()` as a builder method did not compile, and `@Override stage()` compiled only because a
record component of that name existed.
- Proof: compile errors in [#131](https://github.com/true-sim/engine/pull/131) and
  [#135](https://github.com/true-sim/engine/pull/135).
- Rule: give builder methods on records other names (`drawBackFaces()`).
