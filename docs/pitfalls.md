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

**A for-each over a list allocated its iterator in every depth and ID draw.** `Materials.drawsGeometry`, asked by
every visibility buffer ID draw (both culling phases), looped `for (MaterialType type : geometryTypes)` over a
`CopyOnWriteArrayList`: 16 bytes a phase, every frame, for the first 700 or more frames, until the compiler removed the
iterator. Allocation tests that warmed 320 frames read about 45 bytes a frame in any visibility buffer graph.
- Proof: bytes allocated per pass of a frame (the thread's allocated bytes around each pass body, frames 50 to 400):
  "meshlet ids early" and "late" 16 each every frame, none once looped by index; `MotionVectorsTest` counts frames
  allocating and fails with the for-each (true-sim/engine, GI S0).
- Rule: per-frame code loops over lists by index; an allocation test counts the frames that allocate (one in a few
  hundred is the compiler) rather than trusting a warm-up to have compiled everything.

**`Enum.values()` copies its array on every call.** `Format.of` allocated through it.
- Proof: [#132](https://github.com/true-sim/engine/pull/132) (`Format.ALL`).
- Rule: keep `values()` in a static field.

**A `FrameBuffer` written field by field costs about 150 ns a field.** Each `putInt` or `putFloat` writes the master,
the open slot's write-combined copy and, under a lock, the other slots' missed pages. `Particles.update` wrote 300
emitter records and 2,000 CPU-emitter particles that way, about 53,000 writes: 8.3 ms a frame once the torches of the
scene benchmark burned (embers alive), most of the "sync" column.
- Proof: the scene benchmark's torches, timed per stage of `EffectSync.update` over 1,300 frames: `Particles.update`
  8.3 ms before, 0.065 ms with the tables written into native memory and copied into their frame buffers once per
  frame (true-sim/engine, effects CPU cost PR; `particles/Staging`).
- Rule: tables rewritten every frame are written into plain memory and handed to the frame buffer with one `put`;
  per-field writes are for a few fields that change.

**A flag every thread writes ping-pongs between their caches.** Posing skinned copies on the sync's job system (A5),
every matrix value that changed set one `written` field of the `Deformation` (and `changed[copy]` for its copy): the
sync of 1,000 skinned characters (24 joints) took 3.2 ms a frame on 32 threads against 0.5 ms posed one after another,
and the CPU path's blend 2.0 ms against 0.15. Reading the flag first and writing it only when not yet set brought them
to 0.33 and 0.07 ms.
- Proof: `SkinnedCrowdBenchmark` (engine-scene), run before and after the change in the same session (A5 branch).
- Rule: in code run on many threads, a shared flag or counter is read before it is written, or kept per thread.

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

**A render view copied its camera when set, though the docs said it was read when the frame runs.** The demo set its
camera only in its loop, after `SceneView.set`: culling and drawing kept the blank camera's matrices and drew nothing,
while the light clusters read the live camera. Every engine test set the camera first, so none showed it. A headset's
eyes, located inside `Headset.render` just before the frame records, could not have been set in time at all.
- Proof: `DemoSceneTest` and `GraphTechniquesTest.aViewSetOnceFollowsItsCameraAsItMoves` draw only sky without
  `RenderView.refresh` (the culling reads the camera again where it changed), and the scene with it.
- Rule: per-frame inputs that the docs say are read when the frame runs are read there, not copied when handed over;
  test the order an app uses (set once, change later), not only set-then-draw.

**The frame before's view-projection stayed after the camera stopped.** A render view wrote its matrices, and with
them the frame before's (for motion vectors), only in frames where its camera changed. A camera that moved and then
stood kept the last move as the frame before's: every still pixel read 2 pixels of motion for ever after.
- Proof: `MotionVectorsTest`'s "the camera stopped" frame read 2.0 instead of 0 on both shading techniques, and passes
  with the view writing again on the frame's first cull while the frame before's differs (`RenderView.refresh(first)`,
  true-sim/engine, GI S0). A fix writing again on every refresh read 0 for the moving frame too: the late cull's
  refresh of the same frame made the frame before's this frame's (one frame slot counts no frames).
- Rule: a value of the frame before is written every frame, not only when the current one changes; a test of it
  includes a frame after the change stops.

**One technique instance in two views built for one of them only.** The graph keeps each technique's view in an
identity map, so the same `CascadedShadowMaps` added to both eyes' views filled the second view's shadow table twice
and the first view's never: its floor was lit where it should be shadowed, with no error.
- Proof: `ShadowsTest.viewsShareOneSetOfCascadesDrawnOnce` failed (the first view's floor 213 against at most 106)
  until the second view got `cascades.share()`, and passed with only one view.
- Rule: `ViewPort.add` and `FrameGraph.add` refuse an instance that is in another view or the frame already; sharing
  work between views goes through equal requests, each view with a technique of its own.

**Per-phase culling lists are reused, not appended.** The late cull resets the draw commands and writes its visible
meshlets from slot 0 of each bin, over the early phase's. That is right for bins drawn between the phases, wrong for a
bin drawn after both: the blended bin, drawn by the transparency pass after the late cull, lost everything visible the
frame before and drew the newly visible twice.
- Proof: `GraphTechniquesTest.blendedMeshletsStayAcrossOcclusionPhasesAndRenderScales` fails at frame 2 (pure red, the
  blended quad gone) with the blended bin counted per phase and its commands reset by the late cull, and passes with one
  counter for both phases (`visibleCountWord`) and the late cull keeping its commands (true-sim/engine, transparency as
  techniques). A first frame alone does not show it: with no history everything is late.
- Rule: a bin drawn after the late phase accumulates both phases' lists; a test of anything culled in two phases runs
  three frames or more.

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

**On the integrated GPU, drawing a material type other than metallic-roughness can lose the device.** Since the
uploads became a graph pass, `SceneSyncTest` loses it every run there. It comes with forward shading checking the
material's type, whatever the shape of the check; not the barriers, the lean path or the data. Cause not found;
everything checked and measured is in [#151](https://github.com/true-sim/engine/issues/151).

**One `public` in a Slang file without a `module` line hides the rest of it.** environment/filter.slang marked one
function `public`; from then on its other declarations defaulted to internal, and the build's push-constant layout
generator, which imports the file, failed with E30600 "'FilterPush' is not accessible from the current context". Files
without any visibility modifier make everything public, which is what the generator relies on.
- Proof: reproduced while adding environment lighting (true-sim/engine, environment lighting PR); removing the `public`
  fixed the build.
- Rule: in shader files that are entry points rather than modules (no `module` line), use no visibility modifiers; in
  modules, mark everything other files use `public`.

**A `[HostLayout]` struct named through a pointer before it is declared broke the layout generator.** `ParticleFrame`
held a `ParticleSource*` declared after it in particle.slang; `generateSlangRecords` failed with "class
java.lang.String cannot be cast to class java.util.Map" (`GenerateSlangRecords.writeLayouts`): the reflection names a
struct it described already instead of describing it again, and the probe's own field of that struct came second.
- Proof: reproduced while adding mesh shapes (effects step 15); declaring `ParticleSource` before `ParticleFrame` fixed
  the build.
- Rule: declare a `[HostLayout]` struct before any struct holding a pointer to it.

**On lavapipe, a load through a null pointer crashed the process even behind a branch.** The light clusters read the
GPU-written lights' count through a pointer that was null without them: as a ternary (`p == nullptr ? 0 : p[0]`, a
select loading both sides) and then guarded by an `if`, every test with light clusters on CI's lavapipe died with
SIGSEGV in the test JVM; the RX 9070 XT ran both. lavapipe runs shaders on the CPU, so a wild read is a segfault, and
the guarded load still ran, speculated or flattened somewhere between Slang and the driver (not found where).
- Proof: three CI runs of the particle lights PR (effects step 17b) crashed in `engine-render` and `engine-demo`
  tests within seconds; with the pointer always naming a word that holds 0, the run passed.
- Rule: never hand a shader a null pointer it may load through, even behind a branch; point it at a valid empty value.

**A local variable shadowed the parameter a sort key was built from.** simulate.slang's `send` took the parent's slot
as `uint local` and then declared `bool local` (its emitter's space) in the loop; the event key `local << 4 | ...` used
the bool, so events of one parent emitter, phase and event kept the order of the atomic that appended them, not their
parents' order. Slang compiled it without a warning (`-warnings-as-errors all`).
- Proof: a reading of `send` (true-sim/engine, effects step 18's sort work, which renamed the bool `space`); the small
  event sort's test compares two runs' children slot by slot.
- Rule: in shaders, never reuse a parameter's name for a local; Slang does not warn.

**Features and sharing must match the device.** Cube array image views failed validation until `imageCubeArray` was
enabled, and on lavapipe (one queue family for graphics and compute) images failed when they listed that family twice
for concurrent sharing.
- Proof: [#132](https://github.com/true-sim/engine/pull/132); the device enables `imageCubeArray` where present, and
  `GpuImage` lists each family once.
- Rule: run the tests with validation (they fail on any validation error), on lavapipe too.

**Waiting on a fence just made took 8 ms.** `VulkanDevice.submitAndWait` made a command pool and a fence per call.
On an RX 9070 XT (Windows, AMD driver) the wait on the new fence took 8 to 11 ms for a 64 KiB fill, against 0.06 ms
on a reused fence reset each time; every texture, persistent resource and ray-traced mesh set up paid it, 3 to 4 times
per mesh.
- Proof: a probe timing each step of the call (pool 0.04 ms, recording 0.005 ms warm, submit 0.04 ms, wait 8.1 ms
  fresh against 0.06 ms reused); `submitAndWait` went from 11 ms to 0.07 to 0.14 ms with a reused pool and fence
  ([#101](https://github.com/true-sim/engine/issues/101)).
- Rule: reuse fences and command pools, resetting them; never create one per wait.

**A wait for the GPU in a streaming path waits for every frame queued ahead.** `RayTracing.add` made 3 to 4
`submitAndWait` calls per mesh on the graphics queue; with frames in flight each waited for the frames before it too.
Streaming 12 ray-traced meshes a frame took the render thread 6 to 11.6 ms, a 90 Hz headset's whole budget.
- Proof: `StreamBench --args=rays` before and after building bottom levels in frames (true-sim/engine#101's streaming
  work): frames with adds 6.09 ms on average (max 11.59, 25 over 4 ms) against 1.25 (max 1.96, none over 4 ms).
- Rule: what runs while frames do (streaming, adding, removing) records into a frame and frees through the frame
  slots; `submitAndWait` and `waitIdle` are for setup and shutdown only.

**A top level refitted while an instance lost its bottom level read address 0 and lost the device.** Instances
without a bottom level got reference 0 (inactive). When a skinned renderable's copy was dropped and a new one made,
its instance named the new copy, whose level was not built yet, while nothing else the top level is built from had
changed: the top level was refitted with an instance gone from active to inactive. The spec forbids it ("Any
transition between the active and inactive states requires a full acceleration structure rebuild", acceleration
structure update rules); the validation layers reported nothing; the RX 9070 XT faulted reading address 0.
- Proof: `SceneSyncTest.rayTracingTracesTheScenesMeshesAndPosedCopies` lost the device in four runs of four
  ("invalid read at 0x0 ... in no buffer"), and passes with the fix (true-sim/engine, scene ray tracing PR).
- Rule: an instance left out of a top level names an empty bottom level with mask 0, never reference 0; anything else
  that changes which instances are active rebuilds the top level instead of refitting it.

**A cluster hierarchy's finest level is not all of the mesh.** Bottom levels were built from one detail level's
meshlet range (the last, "finest"). In a cluster hierarchy the levels are the DAG's depths: parts that simplify no
further keep their meshlets in coarser levels, so Sponza's finest level held 80 of its 103 submeshes; its floor and
some arches were missing from every ray (the reference showed the sky through the floor, ray-traced shadows lacked
casters), while rasterizing, which selects by error, drew them.
- Proof: per level, the submeshes its meshlets name (Sponza: 103 in all, 80 in the last level); the meshlets whose own
  error is 0 name all 103. `ReferencePathTracerTest.rayTracedLevelsHoldEveryPartOfAClusterHierarchy` (a bumpy grid
  beside a two-triangle quad) sees the sky through the quad without the fix (true-sim/engine, GI S0).
- Rule: a bottom level is built from the level's cut (own error at most the level's, parent's above it), as drawing
  selects, and rays find a triangle's meshlet through that list, not a range.

**A build transform of a flat mesh was not invertible.** Bottom levels scale quantized positions by the mesh's box;
a flat quad's box has no height, so the scale was 0 on one axis (VUID-VkTransformMatrixKHR-matrix-03799). The
transform lives in a buffer, so only GPU-assisted validation saw it; ordinary validation stayed quiet.
- Proof: `gpuAssistedValidation(true)` on `SceneSyncTest` reported the VUID for the test's squares, and nothing with
  a least scale (`BottomLevels.scale`).
- Rule: build transforms never scale by 0; run ray tracing tests with GPU-assisted validation once after changing
  what builds read from buffers.

**A technique that provides nothing is never built.** `SegmentTracing` first provided no key, was added with
`FrameGraph.add` and had a readback pass with side effects: it traced nothing, frame after frame, with no error. The
graph builds only techniques the outputs need, through keys required or modified; side effects keep passes of built
techniques from culling, they do not build a technique.
- Proof: `SegmentTracingTest` collected no batch in 16 frames ("traced 0") until the tracing modified the frame's
  output key (true-sim/engine, ray-traced audio occlusion).
- Rule: a technique whose work only leaves the frame (readbacks, side effects) modifies a key its frames always make
  (`Keys.SCENE_COLOR` of the main view), as `RayTracedShadows` modifies the light list.

**A technique that only read a key required its last version.** `SkyBackground` (at `Anchor.OPAQUE`) required the
scene's depth; `OpaqueTransparency` (at `Anchor.TRANSPARENT`) modifies the depth and the color. Requiring a key builds
every modifier of it first, so the sky needed transparency built, and transparency needed the color after the sky: the
build failed with "Techniques require each other's results in a cycle". `ParticleDraw.beforeGlass` had the same cycle.
- Proof: `FrameGraphTest.aReaderTakesAKeyAsTheTechniquesBeforeItLeftIt` fails with `require` and passes with
  `Technique.reads()`/`GraphBuilder.read`; `SortedTransparencyTest.aSkyBuildsWithEveryTransparency` builds the sky with
  every transparency technique (true-sim/engine, sorted transparency). A reader stands among a key's modifiers, so a
  plain `require` of the key builds it too: transparency then reads the depth in its order as well, or a particle draw
  after it (a reader) was needed before it (`ParticleDrawTest.particlesBeforeGlassAreTintedByItAndThoseAfterItAreNot`).
- Rule: a technique that reads a key at its place in the frame (a background, soft particles, transparency testing the
  opaque depth) lists it in `reads()` and takes it with `g.read`; `require` is for a key's final version.

**A layout computed twice, from two sizes.** The visibility buffer's per-bin tile lists were spaced by the whole image's
tile count where Java dispatched them and by the render region's where `resolve.slang` classified into them. With
dynamic resolution every bin past 0 read another bin's header: its pixels went unshaded, and its indirect dispatch
could take tile words as group counts.
- Proof: `GraphTechniquesTest.theVisibilityBufferShadesEachMaterialTypeWithItsOwnShader` at half the render scale finds
  no glowing pixels without the fix and the quad's with it; the tests at full size and of one bin passed throughout.
- Rule: a buffer layout shared by Java and a shader is computed once and handed over (a push field), never derived on
  both sides; test it with a region smaller than the image and with more than one bin.

**A constant stood in for an integral that depends on the angle and the roughness.** Without an environment, the
sheen's directional albedo (what it reflects of the sky, and takes from the base) was a fixed 0.157, its average at a
mid roughness, and the sheen's roughness was clamped to 0.07: a sheen of roughness 0, which KHR_materials_sheen makes
reflect nothing, dimmed its base by 16 % and added 16 % of its color under the sun and sky (SheenTestGrid's top row
cyan where the Khronos reference shows none).
- Proof: `GraphTechniquesTest.aSheensAlbedoFollowsItsRoughnessUnderTheSunAndSky` fails with the constant (the base's
  red 63 against 75 without a sheen) and passes with `sheenAlbedo`, a fit to the lobe's integral (true-sim/engine,
  PBR fixes from the Khronos samples).
- Rule: an approximation of an integral is fitted to the integral over its whole domain and says how far it is off;
  a path without the lookup table uses the same lobe the table was made from.

**Neighbors compared by depth rejected their own surface on a slanted floor.** Screen-space reflections traced at
half resolution took the 2 x 2 hit texels around a pixel where the pixels they were traced from lay within 5 % of its
depth. On a floor seen at a grazing angle, depth changes by more than that every two rows: every other row found no
hit, and the mirror image came out striped.
- Proof: `ScreenSpaceReflectionsTest.reflectionsFollowTheRenderRegion` ("traced at half resolution": red 0 at the
  mirror image) with the depth test; passes with the distance from the pixel's plane (true-sim/engine, screen-space
  reflections).
- Rule: decide whether a neighbor lies on the same surface by its distance from the pixel's plane, not by depth.

**Blurring the hit by the cone's footprint did not blur an object's outline.** Rough reflections sampled the scene's
mip chain at the level of the GGX cone's footprint where the mirror ray met it. A thin stripe against the sky came out
dimmer but no wider: rays of the pixels beside it missed (the sky) and took the environment, so the outline stayed
where the mirror's was.
- Proof: `ScreenSpaceReflectionsTest.roughFloorsBlurTheirReflections`: the stripe 4 pixels wide on the mirror and on
  the rough floor alike, wider (as the test wants) once the composite averages the hits and misses of the pixels within
  the cone's radius (true-sim/engine, screen-space reflections).
- Rule: a filter of a lobe over one ray per pixel gathers neighbors' rays too, misses included.

**Screen-space reflections tested a visible pixel as if it were the surface the ray met.** Rays near an outline or
under a bulge go behind the visible surface and meet hidden parts of the same object. Three choices made from the
visible pixel alone flipped neighbors between hit and miss: rejecting the hit when the pixel's normal faced away from
the ray (at outlines it is perpendicular to the view, so its sign is noise), taking the pixel under the hierarchical
march's end point (at a cell border it lies on either side by the march's nudge, so half the contact took the floor's
pixel), and a thickness measured along the view (at grazing angles the depth behind a pixel jumps from one pixel to
the next). The demo's screenshots showed a dotted line of sky along each rock's contact with a mirror and hairs along
the reflections' outlines; filling from neighbors then drew a line of sky of its own, from pixels of the rock standing
on the floor, which are untraced but lie on the floor's plane.
- Proof: `ScreenSpaceReflectionsTest.aBoxOnAMirrorReflectsItsContactAndOutlineCleanly`: with the old shader, a rock on
  the mirror showed the environment at 8 contact pixels and 8 to 12 extra hit-miss changes along its outline on every
  path and technique; 0 with the pixel the ray went behind, no normal test, thickness along the surface's normal and a
  3 x 3 reconstruction over texels traced from the same surface (true-sim/engine, screen-space reflections).
- Rule: a test of a screen-space hit uses what the ray did (the pixel it went behind, how deep), not the visible
  pixel's properties; a filter over neighbors leaves out texels not traced at all, not only those of other planes.

**Probes on the planes of axis-aligned walls saw half of nothing.** The probe volume placed probes at whole multiples
of their spacing; walls built on whole meters (the GI test scenes, a box from -1 to 1) put probe rows exactly in
their planes. Rays from such a probe going outwards met nothing (neither a back face to relocate it nor a wall), so
half of its texels took the black environment, and the white furnace read 0.471 instead of 0.5.
- Proof: `ProbeVolumeTest.aClosedBoxGlowingAndReflectingEvenlyIsEOverOneMinusTheAlbedoThroughTheProbes` (5.8 % low)
  passes within 3 % with the probes half a spacing off the grid's planes (true-sim/engine, GI S2).
- Rule: test a probe or sample grid with geometry on its own planes; keep grid points off the coordinates content is
  authored on.

**A change detector averaging relative differences was biased by noise.** The probe volume's drift detector first
averaged each texel's (new - old) / max(new, old): not zero for noise alone, so probes of a still scene restarted
again and again and its frames' noise (16 x 16 blocks against their settled mean) rose from 0.0017 to 0.0085 in the
mover scene; the difference of the texels' sums over the old sum, tested against its own running deviation, kept it
at 0.0018.
- Proof: `GiEvaluation`'s lag table, the mover scene's noise column, before and after (true-sim/engine, GI S2).
- Rule: a statistic that decides to drop history is zero-mean under noise alone, and is tested against its measured
  noise, not a fixed fraction; check a still scene's noise after adding one.

**A radiance cascade's interval was continued by probes whose own intervals start across a wall.** Merging, a
direction whose ray met nothing within its interval took the next cascade's light around the probe (eight probes,
trilinear), as in 2D. Those probes' intervals start where this one's ends only along the same line from the same
point; in 3D, 2 to 4 m from it at the first merge, they started outside rooms 2 m wide (the sun room took the sky:
+178 % mean error), and their four finer directions started up to a fifth of the interval to the side, beyond 5 cm walls
(the leak room's dark side 0.0117 bright, against 0.0001 for the probe volume). Testing that the probes themselves see
each other did not help; the white furnace lost 4 to 7 % of its light to the same continuations.
- Proof: `GiEvaluation` (leak-5cm, sun-room, the furnace of `RadianceCascadesTest`) before and after each test, in
  true-sim/engine's radiance cascades (GI S3): a ray from where the interval ends to where each probe's starts, and from
  where the coarser direction's interval starts to where each finer one's does, leave out what a surface separates:
  leak-5cm 0.0000, sun-room 7.3 %, the furnace within 2.1 %. With intervals long enough that nothing merges, no leak.
- Rule: light continued from other rays is checked to continue the same path: the start of each continuation in sight
  of where the ray it continues ended.

## Tests and measurements

**A GPU time per frame that left out the frame start.** `GpuTimings` timed passes only; textures' copies and mip
chains are recorded in the frame start (`FrameSlots.recordStart`), before every pass, so `StreamBench`'s "GPU per
frame" (1.78 ms at worst for frames streaming a 2048 x 2048 texture) left them out.
- Proof: a reading of `FrameGraph.recordChunks` and `FrameTooling` (timestamps around passes only); since the frame
  start is timed (`GpuTimings.frameStartMillis`), the same frames read 2.4 to 3.2 ms (true-sim/engine#101, streaming
  on the transfer queue).
- Rule: a GPU time meant as "the frame's" adds `frameStartMillis` to the passes' total.

**Freeing a staging buffer on the frame's thread can take milliseconds.** With the transfer queue's staging buffers
freed in `pool.add`, single adds took up to 12 ms (frames of up to 9 to 14 ms in `StreamBench large`); timed, one
free (a shared arena's close and VMA's free of 2.5 to 16 MB) took up to 2.4 ms.
- Proof: timings inside `StreamQueue.release`; with the frees on a thread of their own, the worst `pool.add` fell to
  1.3-3.0 ms and frames over 4 ms from 2-5 to 0 (true-sim/engine#101).
- Rule: free large staging memory off the frame's thread once the GPU is done with it.

**A chunk size derived from the capacity at construction stayed after growth.** The change list prototype sized its
chunks by `min(capacity, chunk bytes / record)`; instance motion's buffers start with 64 records and grow, so their
chunks stayed at 64 records: 469 chunks (and dispatches) for 30,000 motion records, 0.1 ms of uploads that looked like
a cost of writing in place.
- Proof: the per-buffer upload log of the `SceneBenchmark` change list runs (32-byte records, 469 chunks); recomputed
  on growth, 0.007 ms (true-sim/engine#101, the GPU change list).
- Rule: sizes derived from a capacity are derived again when it grows.

**Timing `engine.stepAt` in a test times the engine's pacing.** With no plugin driving frames, every frame waits for
the paced driver (`app.frameRate`, at most 10,000 a second): input's first benchmark read 450 us a frame, the same
work timed through the plugin's own `beforeUpdate` 88 us.
- Proof: `InputBenchmarkTest`, both ways in one run (true-sim/engine, input actions).
- Rule: time a plugin's own frame hook, or subtract an empty frame measured the same way.

**Benchmarks run as tests carry JaCoCo's probes.** The test task runs with the coverage agent: the same input
benchmark read 63 to 92 us a frame with it and 41 to 57 us without (`JacocoTaskExtension` disabled for the run).
- Proof: `InputBenchmarkTest`, three runs each way on the same machine (true-sim/engine, input actions).
- Rule: numbers for the README or the design docs come from a run without the agent, and say so.

**After a device loss, look at the first failure only.** Once one test lost the device, the tests after it failed too,
with zeros and empty images that pointed at unrelated code.
- Proof: [#144](https://github.com/true-sim/engine/pull/144), where 13 renderer tests failed from one bad index.
- Rule: find the first test reporting `VK_ERROR_DEVICE_LOST`, fix that, then rerun.

**One uncounted round did not warm the GPU up for a short benchmark.** `ForwardShadingBenchmark`'s depth draws (about
0.6 ms a frame) took 1.11-1.19 ms in the first counted round and 0.79-0.85 ms in the second on the RX 9070 XT (mesh
shaders), then settled at 0.60-0.68 ms: the ranges of five rounds after one warm-up round spread 0.60-1.19 ms.
- Proof: the per-round lines of `depthByGeometryHooks` with one and with two warm-up rounds (true-sim/engine, geometry
  hooks); with four, the rounds of each configuration fell within 0.08 ms of each other.
- Rule: a benchmark warms until its rounds agree (look at the per-round lines, not the ranges); short frames need more
  warm-up than long ones.

**Benchmark numbers in the README go stale.** The per-path table stayed from the first mesh shader benchmark while
materials and lighting doubled the mesh path's time.
- Proof: [#143](https://github.com/true-sim/engine/issues/143).
- Rule: a change to shared shading or vertex data reruns `MeshletBenchmark` and updates the tables, with the date.

**Platform coverage depends on the video driver.** engine-platform passed its branch coverage locally on the Windows
driver and failed it on CI's offscreen driver (0.70 against 0.80), which reaches fewer branches and has no input
devices.
- Proof: [the failed run of #187](https://github.com/true-sim/engine/actions/runs/41183986316).
- Rule: before pushing platform changes, also run `SDL_VIDEO_DRIVER=offscreen ./gradlew :engine-platform:test
  :engine-platform:jacocoTestReport`, and cover new code with tests that do not depend on the driver.

**Waiting for loads once is not enough.** A load can start another only in a frame: a renderable's materials are asked
for in the `sync.update()` after its mesh uploads. Tests that waited for the loads once, then recorded a few frames,
failed whenever generating the material took longer than those frames (about one run in four).
- Proof: [#142](https://github.com/true-sim/engine/issues/142); with a generator slowed by 200 ms the test failed every
  run, and with `settle` (frames alternating with waiting for the loads they started) it passed, slowed or not.
- Rule: wait with `settle`, not with one `load()` followed by frames.

**After a device loss, ask the driver.** The exception of a lost device now carries the driver's report
(`VK_EXT_device_fault`): the kind of fault, the address and the buffer it falls in. `gpuAssistedValidation(true)` on
the instance has the layer check shader reads and writes as they run.
- Proof: in [#151](https://github.com/true-sim/engine/issues/151) the report showed a read at address 0, in no buffer;
  `VulkanTest.lostDevicesSayWhatTheDriverSaw` and `gpuAssistedValidationRunsWork`.
- Rule: read the report before guessing; turn GPU-assisted validation on when the report names no buffer.

**On AMD a page fault does not stop the GPU.** A fault in a frame's draw pass left the passes after it, and the next
frame's culling, finishing and writing their buffer markers; the device was reported lost later, at a submit.
- Proof: breadcrumbs (`vkCmdWriteBufferMarkerAMD` before and after each pass) during the hunt of
  [#151](https://github.com/true-sim/engine/issues/151): the faulting code was in the forward draw (removing it
  stopped the faults), yet every pass of the frame before had finished.
- Rule: markers show how far the GPU got, not where it faulted; find the faulting code by removing code instead.

**A flaky device loss can be made to fail every run.** Logging while culling was recorded turned a loss seen in about
one run in four into one seen in every run, which made each later experiment a single run.
- Proof: [#151](https://github.com/true-sim/engine/issues/151).
- Rule: when a failure is rare, try slowing or speeding up the CPU side before bisecting.

**An experiment script that silently did not apply its change.** One script replaced the line that set up the
conditions instead of adding to it; two results ("never fails") came from runs without those conditions.
- Proof: the rerun in [#151](https://github.com/true-sim/engine/issues/151) with a script that checks.
- Rule: experiment scripts warn when a change changed nothing and when the setup is missing.

**A benchmark that never fitted its cascades.** `StereoBench` added `CascadedShadowMaps` per eye without calling
`update()` each frame, so the cascades kept their unfitted cameras and shading barely sampled them: with 20,000 rocks
the camera draws read 0.06 ms an eye, cheaper than with 800, and the frame 0.34 ms instead of 2.4.
- Proof: the same benchmark with `update()` every frame (true-sim/engine#216), and the inverted numbers in the stereo
  benchmark's first report (#214) against the fitted ones.
- Rule: a benchmark sets up techniques as an application must (cameras set, `update()`s called); numbers that go the
  wrong way (more work, less time) are a setup bug until shown otherwise.

**A test that counted on the GPU finishing by a frame's end.** `chunksGrowBetweenFramesToWhatFramesWanted` checked
that the chunks had grown as the first frame ended. On the developer's GPU that frame had finished by then; on CI's
software renderer it had not, so the growth came a frame later and the test failed there only.
- Proof: true-sim/engine#246's first CI run (`Expecting value to be false but was true`), green locally; green on CI
  once the test waited for the frames after.
- Rule: what reads the GPU's results at a frame's end (counters, readbacks) sees them a frame or more late; a test
  asserts after enough frames and `waitIdle`, not at the frame that wrote them.

**The sample renders' light clusters binned for a camera that was never set.** `SampleModelsRender` made its
`SceneView` with a blank `Camera` and gave the framed one to `RenderView.set` only; `ClusteredLights` bins for the
`SceneView`'s camera, so no KHR_lights_punctual light reached any surface (LightVisibility's spots and
DiffuseTransmissionPlant's fireflies dark, read as a shading fault). Its lights also stayed at their rest nodes while
the animation moved the fireflies.
- Proof: LightVisibility rendered flat grey before, its two spots after one `Camera` was shared; the plant's leaves
  glow red and green near the fireflies once lights follow the posed nodes (true-sim/engine, PBR fixes from the
  Khronos samples).
- Rule: a view's `SceneView` and its `RenderView.set` take the same `Camera` object; check a light test model before
  judging lighting from a sample render.

**A reflection in a reference-free spot was taken for a bug.** AttenuationTest's smooth glass showed bright patches
(Sample Color 1.5 and 2.0, Thickness Texture 1.5) that the Khronos screenshot does not: mirror reflections, at 4 %
Fresnel, of a band of Footprint Court of radiance about 10 behind the camera. The screenshot's camera is elsewhere.
- Proof: the patches go with the environment's specular term alone (removing transmission, the sun or back faces
  leaves them); the environment looked up along each pixel's mirrored ray predicts a bright band over the top 30 % of
  the 2.0 quad and the 1.5 quad's top-right corner, where they are (`predict.py`, PBR fixes work).
- Rule: before calling a difference from a reference a bug, check what the reference's camera and environment would
  show; reflections depend on both.

## Audio

**Offline audio tests rendered far faster than real time while something loaded or decoded on another thread.**
Three tests passed here and failed only on CI's busy 4-core runner: a loop released at its marker (where the stop fell
depended on when the clip loaded), a streamed loop whose decoder starved 3 times at 26 times real time, and a stinger
that missed its beat because a bar rendered in milliseconds while its clip loaded.
- Proof: true-sim/engine#377's first CI run (`aLoopEndsAtItsNextMarkerOrCrossFades`, 3,600 frames off), CI run
  41599859241 (`introLoopAndStreamedLoopSeamsAreContinuous`, 3 starvations), CI run 41621834713
  (`aStingerPlaysOnTheBeatAndDucksTheMusic`, the bed un-ducked at the beat).
- Rule: a test whose result depends on another thread (asset loads, stream decoders) renders at a device's pace while
  that thread works, or waits for it before the part it measures; never assume it finishes within N blocks rendered
  offline.

**A test's stall sat inside the stream ring's first fill, and its sine's start was read one frame late.**
`aStarvedStreamFadesIsCountedAndResumesWhereItShouldBe` failed twice on CI and passed here.
- First failure: the start was taken as the first non-zero sample, but a sine's first sample is 0, so every expected
  phase was one frame off. It failed whenever the checked sample was not near a peak (0.0127 off against 0.01).
- Second failure: 0 starvations. The decoder stalled at frame 40,000, but the ring (1 s, 48,000 frames) fills at once
  (`StreamRing.fill`). The stall could then begin before the voice read anything. Observation, not reproduced: a voice
  starting more than 0.67 s after the stall began would play the 40,000 buffered frames past its end, and nothing
  starves.
- Proof: CI run 41751716422 (0.2627 against 0.2500), CI run 41771593506 (starvations 0, expected 1 to 2).
- Rule: a test that makes a stream starve puts the stall past the ring's capacity, where the decoder only gets once
  the voice reads. A test that finds where a signal starts accounts for samples that are 0 by design.

**SDL's device thread takes a whole device buffer from the stream at once.** The audio queue was kept at the design's 2
blocks (512 frames); SDL's dummy driver, and this machine's WASAPI device (which ignored
`SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES`), pull 1,024 frames at a time, so the queue ran dry at every pull and the
underrun guard raised the low mark to 5 blocks within 0.1 s. An empty queue was also counted as an underrun when the
device had just taken a buffer the size of the queue, though it had all it needed.
- Proof: `SdlOutputTest.theDummyDriverPlaysBlocksWithoutUnderruns` (three raises logged in its first run; none with
  the floor), and the device probe (`AudioDeviceProbe`: "device buffer 1024 frames", WASAPI) (true-sim/engine, audio
  step 6).
- Rule: the queue's low mark is at least the device buffer plus a block (`SdlOutput.lowFrames`); an underrun is an
  empty queue after more time than the last push and the device buffer covered. Prefill the queue before resuming the
  device, or its first pulls play silence (the disk driver's file had the clip 2,816 frames late without, and 1,024,
  one buffer of silence the disk driver writes first, with).

**In tests, an optimizing compile stops the thread that asked for it.** The allocation-free conventions run every
test JVM with `-XX:-BackgroundCompilation`, so a method's C2 compile blocks its caller until it is done. In
`AudioBenchmark` the slowest block of every scene with voices (17 to 27 ms, against 0.2 to 0.6 ms medians) was such a
compile of a method run every block: `AudioRenderer.renderBlock` reached C2 after about 5,100 blocks (27 s of
audio), past the 10 s warm-up; others were `Voices.render`, `AudioRenderer.drain` and `Voices.choose`. Not GC:
`-Xlog:gc,safepoint` logged no pause or safepoint during those blocks.
- Proof: each slow block's end matched a `b  4` (blocking, tier 4) line for one of those methods in
  `-Xlog:jit+compilation=debug` (e.g. `renderBlock` queued at 4.644 s, the 17.8 ms block ending at 4.661 s); with
  `-XX:+BackgroundCompilation` the same benchmark's maxima were 0.2 to 1.6 ms, and with a 30 s warm-up 0.1 to 3.8 ms
  (true-sim/engine, audio block spikes).
- Rule: a timing test under these conventions warms past the C2 thresholds of what it times (a method called once per
  iteration compiles after about 5,000 calls), or its maximum measures the compiler; `AudioBenchmark` warms 30 s and
  fails on any timed block slower than a block.

**A broadband ILD can be halved by the spectrum, not by the directions.** The ambisonic field's ILD of pink noise
was about half the direct HRTF's in dB (7.2 against 11.3 at 90°), yet per octave band it was close (500 Hz: 5.5
against 5.2 dB). The decode fitted the HRTFs' complex responses (with their interaural delays) at every frequency;
a third-order field cannot carry that phase above about 2 kHz, so the fit lost the highs, and scaling it to the
direct HRTF's loudness raised the lows: the near ear was 11 dB too loud at 500 Hz and 7.5 dB too quiet at 6 kHz, and
a broadband ILD is dominated by the band with the most energy. Raising the order with the same fit did not cure it
(fifth order: +9.8 and -6.3 dB); fitting magnitudes above 1.5 kHz (MagLS) did (pink noise at 90°: 11.1 against 11.4).
- Proof: `AmbisonicsTest.theFieldKeepsEachBandsLevelDifference` and the 1.5 dB broadband check in
  `sourcesPastTheCapAreHeardFromTheirDirection` fail with the old decode and pass with MagLS; numbers from
  `AmbisonicsIldProbe` (true-sim/engine, ambisonics ILD).
- Rule: judge a spatial decode per band and per ear level, not by one broadband ratio.

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
- Proof: `CommandsTest.validationErrorsInsideCommandsAreReportedNotFatal` crashes the test JVM with the option and
  passes without it ([#152](https://github.com/true-sim/engine/issues/152)).
- Rule: no `critical` on Vulkan downcalls.
