package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.lwjgl.system.MemoryStack.stackPush;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.common.math.Vec3;
import com.truesim.engine.render.materials.TestFogParameters;
import com.truesim.engine.render.materials.TestGlowLight;
import com.truesim.engine.render.materials.TestGlowParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.lwjgl.system.MemoryStack;

/**
 * Shading hooks from another project's shaders, as a library's are: the test sources' hooks (test/hooks/testFog.slang,
 * a media hook; testGlow.slang, an indirect one) are merged with the engine's into TestEngineShadingHooks, whose
 * variants of the engine's shading the device loads ({@link ShadingHooks#use}). Each way of shading runs them (forward,
 * the visibility buffer, both blended transparencies, on every geometry path), and a hook the view does not turn on is
 * left out of its pipelines (SHADING_HOOKS): its data written, its image the same as without it.
 */
class ShadingHooksTest {

    private static final int SIZE = 32;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    /** The fog's in-scattered light, linear: with no transmittance, what every shaded pixel shows. */
    private static final float[] FOG = {0.2f, 0.4f, 0.6f};

    /** The ways of shading a view. */
    enum Shading { FORWARD, VISIBILITY_BUFFER, WEIGHTED_BLENDED, SORTED }

    private TestGpu gpu;
    private VulkanDevice device;
    private TestRenderer rig;
    private Textures textures;
    private Materials materials;
    private Environment environment;
    private int quad;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        device = gpu.device();
        ShadingHooks.use(device, TestEngineShadingHooks.HOOKS); // before any pipeline is made
        rig = gpu.closing(TestRenderer.of(device, SIZE, SIZE));
        quad = rig.add(TestMeshes.quad());
        textures = gpu.closing(new Textures(device));
        materials = gpu.closing(new Materials(device, 8));
        int sky = textures.add(TextureFormat.RGBA16F, 16, 8, Textures.GENERATE, uniformSky(16, 8));
        environment = Environment.panorama("grey sky", sky, 16);
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            rig.scene().setMaterials(null);
            gpu.close(); // fails on validation errors
        }
    }

    @Test
    void theListingNamesTheHooksByIndexAndInstallsItsVariants() {
        ShadingHooks hooks = ShadingHooks.of(device);
        assertThat(hooks.hooks()).containsExactly(ProbeVolume.HOOK, "test.fog", "test.glow"); // the library's first
        assertThat(hooks.index("test.glow")).isEqualTo(2);
        assertThat(device.shaderVariants().resolve("shaders/material-types/metallicRoughnessForward.spv"))
            .isEqualTo("shaders/shading-hooks/TestEngineShadingHooks/material-types/metallicRoughnessForward.spv");
        assertThat(device.shaderVariants().resolve("shaders/ao/ao.spv")).endsWith("ao/ao.hooks.spv");
        assertThat(hooks.parameters(hooks.index("test.glow"))).isEqualTo(TestGlowParameters.class);
        assertThat(hooks.bytes(hooks.index("test.fog"))).isEqualTo(TestFogParameters.BYTES);
        assertThat(EngineShadingHooks.HOOKS.hooks()).containsExactly(ProbeVolume.HOOK);
        assertThatThrownBy(() -> EngineShadingHooks.HOOKS.require("test.fog")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without test.fog");
    }

    @ParameterizedTest
    @EnumSource(Shading.class)
    void aMediaHookChangesWhatEveryWayOfShadingShows(Shading shading) {
        assumeShading(shading);
        boolean blended = shading == Shading.WEIGHTED_BLENDED || shading == Shading.SORTED;
        // Opaque matte white, or glowing glass at half alpha over black: the fog leaves the in-scattered light alone
        int material = materials.add(blended ? new Material(MR.withBaseColor(new Color(0, 0, 0, 0.5f))
            .withEmissive(new Color(1, 1, 1, 1))).alphaBlend().doubleSided(true)
            : new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(1).withMetallic(0)).doubleSided(true));
        SceneView main = view(shading, material);
        main.port().add(new Hooked("test.fog", true, new TestFogParameters(new Vec3(0, 0, 0),
            new Vec3(FOG[0], FOG[1], FOG[2]))));
        for (MeshletRenderer.Path path : paths()) {
            rig.renderer().setPath(path);
            rig.frames(3);
            int[] center = rig.target().rgba(SIZE / 2, SIZE / 2);
            float share = blended ? 0.5f : 1; // the glass's alpha over the black behind
            for (int k = 0; k < 3; k++) {
                assertThat((double) center[k]).as("%s on %s, channel %d", shading, path, k)
                    .isCloseTo(255 * share * FOG[k], org.assertj.core.api.Assertions.within(2.0));
            }
            assertThat(rig.target().rgba(0, 0)[0]).as("nothing shaded at the corner: the clear color").isZero();
        }
        rig.renderer().setPath(MeshletRenderer.Path.AUTO);
    }

    @ParameterizedTest
    @EnumSource(value = Shading.class, names = {"FORWARD", "VISIBILITY_BUFFER"})
    void anIndirectHookGivesTheLightFromAllAround(Shading shading) {
        assumeShading(shading);
        int material = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(1)
            .withMetallic(0)).doubleSided(true));
        SceneView main = view(shading, material);
        Hooked glow = new Hooked("test.glow", true, TestGlowParameters.DEFAULTS.withVisibility(0));
        main.port().add(glow);
        rig.frames(3);
        int dark = rig.target().rgba(SIZE / 2, SIZE / 2)[1];
        glow.parameters = TestGlowParameters.DEFAULTS.withVisibility(0)
            .withLight(new TestGlowLight(new Vec3(0.25, 0.25, 0.25))); // read as frames record
        rig.frames(2);
        int lit = rig.target().rgba(SIZE / 2, SIZE / 2)[1];
        assertThat(lit).as("%s: the hook's light from all around adds to the sun's", shading).isGreaterThan(dark + 20);
    }

    @ParameterizedTest
    @EnumSource(Shading.class)
    void aHookTheViewDoesNotRunIsLeftOut(Shading shading) {
        assumeShading(shading);
        boolean blended = shading == Shading.WEIGHTED_BLENDED || shading == Shading.SORTED;
        Material surface = new Material(MR.withBaseColor(new Color(0.8f, 0.5f, 0.3f, 0.6f)).withRoughness(0.5f))
            .doubleSided(true);
        int material = materials.add(blended ? surface.alphaBlend() : surface);
        SceneView main = view(shading, material);
        rig.frames(3);
        BufferedImage without = rig.target().shot();
        // Its data written into its slot of the table every frame, but not turned on for the view
        main.port().add(new Hooked("test.fog", false, new TestFogParameters(new Vec3(0, 0, 0), new Vec3(1, 1, 1))));
        rig.frames(3);
        BufferedImage with = rig.target().shot();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertThat(with.getRGB(x, y)).as("%s, pixel %d, %d", shading, x, y).isEqualTo(without.getRGB(x, y));
            }
        }
        assertThat(rig.target().rgba(SIZE / 2, SIZE / 2)[0]).as("the quad is drawn").isGreaterThan(20);
    }

    /** A view of the quad 2 m ahead in {@code material}, shaded by {@code shading}, lit by the grey sky. */
    private SceneView view(Shading shading, int material) {
        rig.scene().setMaterials(materials);
        rig.scene().set(0, quad, material, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
        SceneView main = rig.toTarget(rig.main(new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1)));
        SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
        Technique opaque = shading == Shading.VISIBILITY_BUFFER
            ? new VisibilityBufferShading(geometry, main, TestRenderer.COLOR_FORMAT, TestRenderer.DEPTH_FORMAT)
                .clearColor(0, 0, 0)
            : rig.forward(main).clearColor(0, 0, 0);
        main.port().add(opaque).add(new EnvironmentLighting(environment, main,
            new EnvironmentMaps.Quality(64, 32, 4, 16, 64)));
        if (shading == Shading.WEIGHTED_BLENDED) {
            main.port().add(new WeightedBlendedTransparency(geometry, main));
        } else if (shading == Shading.SORTED) {
            main.port().add(new SortedTransparency(geometry, main));
        }
        return main;
    }

    private void assumeShading(Shading shading) {
        org.junit.jupiter.api.Assumptions.assumeTrue(shading != Shading.VISIBILITY_BUFFER || device.primitiveIds(),
            "the visibility buffer needs primitive IDs in fragment shaders");
    }

    private List<MeshletRenderer.Path> paths() {
        List<MeshletRenderer.Path> paths = new ArrayList<>(List.of(MeshletRenderer.Path.INDEXED));
        if (device.meshShaders()) {
            paths.add(MeshletRenderer.Path.MESH_SHADERS);
        }
        return paths;
    }

    /** A panorama of half floats, mid grey everywhere. */
    private static MemorySegment uniformSky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < width * height; i++) {
            for (int k = 0; k < 3; k++) {
                texels.set(half, 8L * i + 2L * k, Float.floatToFloat16(0.5f));
            }
            texels.set(half, 8L * i + 6, Float.floatToFloat16(1));
        }
        return texels;
    }

    /**
     * A test's technique adding a shading hook to its view, as a library's does ({@link ShadingHooks#add}): its
     * parameters' record from {@code parameters}, read each frame. Without {@code run}, the same record and its address
     * written into the view's table, but the hook not turned on for the view.
     */
    private final class Hooked implements Technique {
        private final String hook;
        private final boolean run;
        private volatile ShadingHookParameters parameters;

        Hooked(String hook, boolean run, ShadingHookParameters parameters) {
            this.hook = hook;
            this.run = run;
            this.parameters = parameters;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.INDIRECT_LIGHT);
        }

        @Override
        public void build(GraphBuilder g) {
            ShadingHooks hooks = ShadingHooks.of(device);
            ShadingHookSource source = ShadingHookSource.of(parameters.bytes(), () -> parameters);
            if (run) {
                hooks.add(g, hook, source);
                return;
            }
            int index = hooks.require(hook);
            Buffer table = g.modify(Keys.INDIRECT_LIGHT);
            Buffer record = g.createBuffer("test record", source.bytes());
            MemorySegment values = Arena.ofAuto().allocate(source.bytes(), 16);
            g.pass("test hook, not run").write(table, BufferAccess.TRANSFER_DST)
                .write(record, BufferAccess.TRANSFER_DST).record(ctx -> {
                    source.write(ctx, values, 0);
                    Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(record), 0, values.byteSize(),
                        values.address());
                    try (MemoryStack stack = stackPush()) {
                        long address = stack.nmalloc(8, 8);
                        org.lwjgl.system.MemoryUtil.memPutLong(address, ctx.address(record));
                        Commands.updateBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(table),
                            ShadingHooks.entry(index), 8, address);
                    }
                });
            g.request(new IndirectInputs(g.view())).read(record);
        }
    }
}
