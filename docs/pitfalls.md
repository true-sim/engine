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

**In depth-only mesh pipelines the fragment shader did not see the mesh shader's primitive ID** (RX 9070 XT, driver
32.0.31041.1004). In a pipeline without color attachments, the fragment shader's `SV_PrimitiveID` was not the value the
mesh shader wrote; with color attachments it was. The same code on lavapipe (Mesa 26.0.8, in CI)
delivers it, so it is specific to this GPU or driver. The SPIR-V meets the spec's rules for `PrimitiveId`
(VUID-PrimitiveId-07040, -10595, -10596, VUID-Fragment-04332, -04333; checked against Vulkan-Docs on 2026-10-30),
`geometryShader` is enabled and the validation layers report nothing. Not tried: a minimal program outside the
engine, which a report to AMD would need. A masked shadow map that found its
triangle through it read out of bounds and lost the device (`ShadowsTest.maskedTexelsCastNoShadow`, every run).
- Proof: the mesh shader wrote 777 for every triangle and the fragment shader discarded any other value: every
  fragment was discarded; on lavapipe they passed (draft [#149](https://github.com/true-sim/engine/pull/149), the masked
  quad casting its shadow). Barycentrics are not involved (the same without them), and with no memory read the device
  stays. With color attachments the lean path depends on the primitive ID, and its images match the indexed path's
  (`bothPathsShadeTheSameImage`). Experiments in [#147](https://github.com/true-sim/engine/pull/147).
- Rule: depth-only mesh pipelines take nothing from the primitive ID; the masked bin's depth uses `meshIdsMain` with
  draw.slang's `maskedDepthMain`.

**On the integrated GPU, a struct of buffer pointers loaded in one piece came back wrong** (AMD Radeon(TM) Graphics of a
Ryzen 9 9950X3D, drivers 32.0.21045.5002 and 32.0.21045.16010). Culling loaded a view's four list pointers as one
struct (one `OpLoad`): the first was right, the other three read as their low 32 bits twice (`0x3008b2050` as
`0x008b2050_008b2050`), so culling wrote far outside its lists. The light list's header, read as one struct, broke
classic shadows on the same GPU. The RX 9070 XT runs the same SPIR-V correctly; it passes `spirv-val` and the
validation layers report nothing.
- Proof: the values read back on the integrated GPU; one load per pointer fixed culling, and reading the header field
  by field fixed the shadows ([#152](https://github.com/true-sim/engine/issues/152)). Reported on
  [AMD-Gfx-Drivers#115](https://github.com/GPUOpen-Drivers/AMD-Gfx-Drivers/issues/115), where a similar load on an
  integrated GPU reads zeros.
- Rule: load buffer pointers one at a time, never as a struct holding several.

**On the integrated GPU, a mesh shader calling `SetMeshOutputCounts` twice drew nothing.** The mesh entry points
returned early past the end of their list with `SetMeshOutputCounts(0, 0)` and called it again further on. Each
invocation reached one call and the branch was the same for the whole workgroup, which the extension arguably allows
(once, in uniform control flow). On the integrated GPU the full mesh path drew nothing and the lean path about half;
with one call per entry point, both draw. The RX 9070 XT drew either way.
- Proof: the renderer tests on the integrated GPU, before and after
  ([#152](https://github.com/true-sim/engine/issues/152)).
- Rule: one `SetMeshOutputCounts` per mesh entry point, reached by every invocation; no early return before it.

**On the integrated GPU, a fragment shader's `float4` loads through a pointer from push constants failed.** In the
custom pass test's shader, stepping a `float4` pointer lost the device, and a `uint` step followed by a `float4` load
read 0; three 4-byte loads read the right values.
- Proof: `CustomPassTest` on the integrated GPU ([#152](https://github.com/true-sim/engine/issues/152)).
- Rule: where such a load misbehaves on that GPU, read the values as 4-byte words.

**Storage writes from a mesh shader did not arrive on the integrated GPU**, also with
`vertexPipelineStoresAndAtomics` enabled, so debugging values written from a mesh shader read back unchanged.
- Proof: experiments while working on [#152](https://github.com/true-sim/engine/issues/152).
- Rule: write debugging values from a fragment or compute shader.

**On the integrated GPU, drawing a material type other than metallic-roughness can lose the device.** Cause not found;
everything checked and measured is in [#151](https://github.com/true-sim/engine/issues/151).

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

**Waiting for loads once is not enough.** A load can start another only in a frame: a renderable's materials are asked
for in the `sync.update()` after its mesh uploads. Tests that waited for the loads once, then recorded a few frames,
failed whenever generating the material took longer than those frames (about one run in four).
- Proof: [#142](https://github.com/true-sim/engine/issues/142); with a generator slowed by 200 ms the test failed every
  run, and with `settle` (frames alternating with waiting for the loads they started) it passed, slowed or not.
- Rule: wait with `settle`, not with one `load()` followed by frames.

## Java

**A record's components are its accessors.** A method named like a component must return the component's type:
`GraphicsProgram.doubleSided()` as a builder method did not compile, and `@Override stage()` compiled only because a
record component of that name existed.
- Proof: compile errors in [#131](https://github.com/true-sim/engine/pull/131) and
  [#135](https://github.com/true-sim/engine/pull/135).
- Rule: give builder methods on records other names (`drawBackFaces()`).

**Critical downcalls crash the JVM when the validation layer reports.** `Commands` made its handles with
`Linker.Option.critical`. The layer reports an error from inside the faulty call, through the instance's debug callback,
an upcall; an upcall out of a critical downcall kills the JVM instead of logging the error.
- Proof: `CommandsTest.validationErrorsInsideCommandsAreReportedNotFatal` crashes the test JVM with the option and passes
  without it ([#152](https://github.com/true-sim/engine/issues/152)).
- Rule: no `critical` on Vulkan downcalls.
