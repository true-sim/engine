package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.AoPush;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.TestFogParameters;
import com.truesim.engine.render.materials.TestGlowLight;
import com.truesim.engine.render.materials.TestGlowParameters;
import com.truesim.engine.render.materials.TestTintParameters;
import com.truesim.engine.render.materials.TransmissionParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.Specialization;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Shading hooks from another project's shaders, as a library's are: the test sources' hooks (test/hooks/testFog.slang, a
 * media hook; testGlow.slang, an indirect one; testTint.slang, a surface one) are merged with the engine's into
 * TestEngineShadingHooks, which the device takes as the listing holding every other on the classpath, its variants in
 * place of the engine's shading. Each way of shading runs them (forward, the visibility buffer, both blended
 * transparencies, on every geometry path); a hook the view does not run is left out of its pipelines, or skipped by its
 * bit; the scene's values run a hook with no technique; surface hooks keep the alpha and the opted out.
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
    void theListingHoldingEveryOtherIsFoundOnTheClasspathWithItsVariants() {
        ShadingHooks hooks = ShadingHooks.of(device);
        assertThat(hooks.name()).isEqualTo("TestEngineShadingHooks");
        // The library's first; then the project's by file (fog, glow, tint), test.tint ordered before test.fog by its
        // attribute
        assertThat(hooks.hooks()).containsExactly(ProbeVolume.HOOK, "test.glow", "test.tint", "test.fog");
        assertThat(device.shaderVariants().resolve("shaders/material-types/metallicRoughnessForward.spv"))
            .isEqualTo("shaders/shading-hooks/TestEngineShadingHooks/material-types/metallicRoughnessForward.spv");
        assertThat(device.shaderVariants().resolve("shaders/ao/ao.spv")).endsWith("ao/ao.hooks.spv");
        assertThat(hooks.parameters(hooks.index("test.glow"))).isEqualTo(TestGlowParameters.class);
        assertThat(hooks.bytes(hooks.index("test.fog"))).isEqualTo(TestFogParameters.BYTES);
        assertThat(EngineShadingHooks.HOOKS.hooks()).containsExactly(ProbeVolume.HOOK);
        assertThatThrownBy(() -> EngineShadingHooks.HOOKS.require("test.fog")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without test.fog");
        // Replacements come before the first shader, never after
        assertThatThrownBy(() -> device.shaderVariants().replace("shaders/ao/ao.spv", "x.spv"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("loaded shaders already");
        assertThat(ShadingHooks.use(device, hooks)).isSameAs(hooks);
        assertThatThrownBy(() -> ShadingHooks.use(device, EngineShadingHooks.HOOKS))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
    }

    @Test
    void twoListingsNeitherHoldingTheOtherFailNamingTheSettingThatListsThemAll() {
        ShadingHooks fog = ShadingHooks.of("FogHooks", new String[] {"lib.fog"}, new Class<?>[] {TestFogParameters.class},
            new int[] {TestFogParameters.BYTES}, ShadingHooks.FIRST_SWITCH, new String[0]);
        ShadingHooks glow = ShadingHooks.of("GlowHooks", new String[] {"lib.glow"},
            new Class<?>[] {TestGlowParameters.class}, new int[] {TestGlowParameters.BYTES}, ShadingHooks.FIRST_SWITCH,
            new String[0]);
        ShadingHooks both = ShadingHooks.of("GameShadingHooks", new String[] {"lib.fog", "lib.glow"},
            new Class<?>[] {TestFogParameters.class, TestGlowParameters.class},
            new int[] {TestFogParameters.BYTES, TestGlowParameters.BYTES}, ShadingHooks.FIRST_SWITCH, new String[0]);
        assertThatThrownBy(() -> ShadingHooks.choose(List.of(fog, glow))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FogHooks [lib.fog]").hasMessageContaining("GlowHooks [lib.glow]")
            .hasMessageContaining("slang { shadingHooks = ");
        assertThat(ShadingHooks.choose(List.of(fog, both, glow))).isSameAs(both);
        assertThat(ShadingHooks.choose(List.of())).isSameAs(EngineShadingHooks.HOOKS);
    }

    @Test
    void aPipelineOfAHookedShaderMadeWithoutTheHooksSwitchesIsRefused() {
        try (ShaderModule ao = ShaderModule.load(device, "shaders/ao/ao.spv")) {
            assertThat(ao.constantIds()).contains(Specialization.REQUIRED_FROM);
            assertThatThrownBy(() -> Pipeline.compute(device, ao, "compositeMain", AoPush.BYTES, 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("constant 1024");
            ShadingHooks hooks = ShadingHooks.of(device);
            try (Pipeline made = Pipeline.compute(device, ao, "compositeMain", AoPush.BYTES,
                hooks.specialization(ShadingHooks.Set.NONE, 0, 1))) {
                assertThat(made).isNotNull();
            }
        }
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
        main.port().add(new Hooked("test.fog", new TestFogParameters(new Vec3(0, 0, 0),
            new Vec3(FOG[0], FOG[1], FOG[2]))));
        for (MeshletRenderer.Path path : paths()) {
            rig.renderer().setPath(path);
            rig.frames(3);
            int[] center = rig.target().rgba(SIZE / 2, SIZE / 2);
            float share = blended ? 0.5f : 1; // the glass's alpha over the black behind
            for (int k = 0; k < 3; k++) {
                assertThat((double) center[k]).as("%s on %s, channel %d", shading, path, k)
                    .isCloseTo(255 * share * FOG[k], within(2.0));
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
        Hooked glow = new Hooked("test.glow", TestGlowParameters.DEFAULTS.withVisibility(0));
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
    void aHookTheViewDoesNotRunIsLeftOutOrSkippedByItsBitAlike(Shading shading) {
        assumeShading(shading);
        boolean blended = shading == Shading.WEIGHTED_BLENDED || shading == Shading.SORTED;
        Material surface = new Material(MR.withBaseColor(new Color(0.8f, 0.5f, 0.3f, 0.6f)).withRoughness(0.5f))
            .doubleSided(true);
        int material = materials.add(blended ? surface.alphaBlend() : surface);
        SceneView main = view(shading, material);
        rig.frames(3);
        BufferedImage without = rig.target().shot();
        // Added, so the view's pipelines are made with it, its record written each frame, but off: its bit clear
        Hooked fog = new Hooked("test.fog", new TestFogParameters(new Vec3(0, 0, 0), new Vec3(1, 1, 1)));
        fog.active = false;
        main.port().add(fog);
        rig.frames(3);
        assertSame(without, rig.target().shot(), shading + ", compiled in, off");
        int builds = rig.graph().builds();
        fog.active = true; // on and off from frame to frame: no new graph, no new pipelines
        rig.frames(2);
        assertThat(rig.target().rgba(SIZE / 2, SIZE / 2)[0]).as("fogged white (blended: at its alpha, 0.6)")
            .isGreaterThan(blended ? 150 : 250);
        fog.active = false;
        rig.frames(2);
        assertSame(without, rig.target().shot(), shading + ", off again");
        assertThat(rig.graph().builds()).as("turning a hook on and off rebuilds nothing").isEqualTo(builds);
    }

    @ParameterizedTest
    @EnumSource(value = Shading.class, names = {"FORWARD", "VISIBILITY_BUFFER"})
    void everyViewMadeWithEveryHookShowsWhatItsOwnPipelinesShow(Shading shading) {
        assumeShading(shading);
        int material = materials.add(new Material(MR.withBaseColor(new Color(0.8f, 0.5f, 0.3f, 1)).withRoughness(0.5f))
            .doubleSided(true));
        SceneView main = view(shading, material);
        main.port().add(new Hooked("test.glow", TestGlowParameters.DEFAULTS.withVisibility(0.5f)
            .withLight(new TestGlowLight(new Vec3(0.1, 0.2, 0.3)))));
        rig.frames(3);
        BufferedImage own = rig.target().shot();
        // The union of every view's hooks: fog and tint too, off in this view by their bits
        rig.graph().settings().set(ShadingHookInputs.SPECIALIZE, ShadingHookInputs.Specialize.UNION);
        ShadingHookValues.of(device).set("test.tint", new TestTintParameters(new Vec3(0, 0, 0), 0), false);
        rig.frames(4);
        assertSame(own, rig.target().shot(), shading + ", made with the union");
        rig.graph().settings().set(ShadingHookInputs.SPECIALIZE, ShadingHookInputs.Specialize.VIEW);
        ShadingHookValues.of(device).remove("test.tint");
    }

    @ParameterizedTest
    @EnumSource(Shading.class)
    void theScenesValuesRunAHookWithNoTechnique(Shading shading) {
        assumeShading(shading);
        boolean blended = shading == Shading.WEIGHTED_BLENDED || shading == Shading.SORTED;
        int material = materials.add(blended ? new Material(MR.withBaseColor(new Color(0, 0, 0, 0.5f))
            .withEmissive(new Color(1, 1, 1, 1))).alphaBlend().doubleSided(true)
            : new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(1).withMetallic(0)).doubleSided(true));
        view(shading, material);
        ShadingHookValues values = ShadingHookValues.of(device);
        // By field name, as content holds them; the transmittance at its default of 1 leaves the surface showing
        values.set("test.fog", new Values(Map.of("transmittance", new float[] {0, 0, 0},
            "light", new float[] {FOG[0], FOG[1], FOG[2]})), true);
        try {
            rig.frames(3);
            float share = blended ? 0.5f : 1;
            assertThat((double) rig.target().rgba(SIZE / 2, SIZE / 2)[1]).as("%s, fogged by the scene's values", shading)
                .isCloseTo(255 * share * FOG[1], within(2.0));
            int builds = rig.graph().builds();
            values.on("test.fog", false);
            rig.frames(2);
            assertThat(rig.target().rgba(SIZE / 2, SIZE / 2)[1]).as("off: the surface again").isGreaterThan(100);
            assertThat(rig.graph().builds()).as("its being on read each frame").isEqualTo(builds);
        } finally {
            values.remove("test.fog");
        }
    }

    @Test
    void aTechniqueMergesTheScenesValuesWithItsOwn() {
        int material = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(1)
            .withMetallic(0)).doubleSided(true));
        SceneView main = view(Shading.FORWARD, material);
        ShadingHookValues values = ShadingHookValues.of(device);
        values.set("test.fog", new TestFogParameters(new Vec3(1, 1, 1), new Vec3(FOG[0], FOG[1], FOG[2])), true);
        // The technique takes the scene's light and puts its own transmittance in
        main.port().add(new Technique() {
            @Override
            public List<Key<?>> provides() {
                return List.of();
            }

            @Override
            public List<Key<?>> modifies() {
                return List.of(Keys.SHADING_HOOKS);
            }

            @Override
            public void build(GraphBuilder g) {
                ShadingHooks.of(device).add(g, "test.fog", new ShadingHookSource() {
                    @Override
                    public int bytes() {
                        return TestFogParameters.BYTES;
                    }

                    @Override
                    public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into,
                        long offset) {
                        ((TestFogParameters) authored).withTransmittance(new Vec3(0, 0, 0)).write(into, offset);
                    }
                });
            }
        });
        try {
            rig.frames(3);
            int[] center = rig.target().rgba(SIZE / 2, SIZE / 2);
            for (int k = 0; k < 3; k++) {
                assertThat((double) center[k]).as("channel %d", k).isCloseTo(255 * FOG[k], within(2.0));
            }
        } finally {
            values.remove("test.fog");
        }
    }

    @Test
    void glassTakesTheMediaOnceForTheSceneSeenThroughIt() {
        // An opaque emissive wall behind a quad of clear glass, under fog halving everything and adding light
        int wall = materials.add(new Material(MR.withBaseColor(new Color(0, 0, 0, 1)).withRoughness(1)
            .withEmissive(new Color(0.8f, 0.8f, 0.8f, 1))).doubleSided(true));
        int glass = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withRoughness(0.02f)
            .withMetallic(0)).feature(TransmissionParameters.DEFAULTS.withTransmissionFactor(1)).doubleSided(true));
        SceneView main = view(Shading.SORTED, wall);
        rig.scene().set(0, quad, wall, 0, 0, -3, 0, 0, 0, 1, 3, 3, 3);
        TestFogParameters fog = new TestFogParameters(new Vec3(0.5, 0.5, 0.5), new Vec3(0.2, 0.2, 0.2));
        main.port().add(new Hooked("test.fog", fog));
        rig.frames(3);
        int behind = rig.target().rgba(SIZE / 2, SIZE / 2)[0]; // the wall through the fog: 0.8 x 0.5 + 0.2
        rig.scene().set(1, quad, glass, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
        rig.frames(4);
        int through = rig.target().rgba(SIZE / 2, SIZE / 2)[0];
        // Through the glass: what it lets through (1 - F, F about 0.04 head on) of the fogged wall, once; fogged twice
        // it would be (0.96 x 0.6) x 0.5 + 0.2 = 0.49, far from 0.96 x 0.6 = 0.58 (its own reflection adds a little)
        double once = 0.96 * behind;
        assertThat((double) through).as("the wall through glass, fogged once (wall alone %d)", behind)
            .isCloseTo(once, within(255 * 0.04));
    }

    @Test
    void theSkyIsSeenThroughTheMediaAsAPointAtInfinity() {
        int material = materials.add(new Material(MR));
        SceneView main = view(Shading.FORWARD, material);
        rig.scene().removeFrom(0);
        main.port().add(new SkyBackground(environment, main, new EnvironmentMaps.Quality(64, 32, 4, 16, 64)));
        rig.frames(3);
        int clear = rig.target().rgba(SIZE / 2, SIZE / 2)[1];
        main.port().add(new Hooked("test.fog", new TestFogParameters(new Vec3(0.5, 0.5, 0.5),
            new Vec3(0.2, 0.2, 0.2))));
        rig.frames(3);
        int fogged = rig.target().rgba(SIZE / 2, SIZE / 2)[1];
        assertThat((double) fogged).as("the sky %d through the fog (the target linear)", clear)
            .isCloseTo(clear * 0.5 + 0.2 * 255, within(2.0));
    }

    @ParameterizedTest
    @EnumSource(Shading.class)
    void aSurfaceHookChangesSurfacesBeforeTheyAreLitButNotTheirAlphaNorTheOptedOut(Shading shading) {
        assumeShading(shading);
        boolean blended = shading == Shading.WEIGHTED_BLENDED || shading == Shading.SORTED;
        Material white = new Material(MR.withBaseColor(new Color(1, 1, 1, blended ? 0.5f : 1)).withRoughness(1)
            .withMetallic(0)).doubleSided(true);
        int plain = materials.add(blended ? white.alphaBlend() : white);
        int optedOut = materials.add((blended ? white.alphaBlend() : white).surfaceHooks(false));
        SceneView main = view(shading, plain);
        rig.frames(3);
        int[] without = rig.target().rgba(SIZE / 2, SIZE / 2);
        ShadingHookValues values = ShadingHookValues.of(device);
        values.set("test.tint", new TestTintParameters(new Vec3(0.25, 1, 1), 0), true);
        try {
            rig.frames(3);
            int[] tinted = rig.target().rgba(SIZE / 2, SIZE / 2);
            assertThat(tinted[0]).as("%s: red quartered before lighting", shading).isLessThan(without[0] - 40);
            assertThat(tinted[1]).as("green as it was").isCloseTo(without[1], within(2));
            if (blended) {
                assertThat(tinted[1]).as("still half covering: its alpha kept").isLessThan(240);
            }
            rig.scene().set(0, quad, optedOut, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            rig.frames(3);
            assertThat(rig.target().rgba(SIZE / 2, SIZE / 2)[0]).as("a material opting out")
                .isCloseTo(without[0], within(2));
            rig.scene().set(0, new GpuScene.Instance().mesh(quad).material(plain).position(0, 0, -2)
                .surfaceHooks(false));
            rig.frames(3);
            assertThat(rig.target().rgba(SIZE / 2, SIZE / 2)[0]).as("an instance opting out")
                .isCloseTo(without[0], within(2));
        } finally {
            values.remove("test.tint");
        }
    }

    @Test
    void aSurfaceHooksCoatOnlyWhereTheMaterialAllowsIt() {
        Material grey = new Material(MR.withBaseColor(new Color(0.2f, 0.2f, 0.2f, 1)).withRoughness(1)
            .withMetallic(0)).doubleSided(true);
        int plain = materials.add(grey);
        int coated = materials.add(grey.hookCoat(true));
        view(Shading.FORWARD, plain);
        ShadingHookValues values = ShadingHookValues.of(device);
        values.set("test.tint", new TestTintParameters(new Vec3(1, 1, 1), 1), true);
        try {
            rig.frames(3);
            int dry = rig.target().rgba(SIZE / 2, SIZE / 2)[1];
            rig.scene().set(0, quad, coated, 0, 0, -2, 0, 0, 0, 1, 1, 1, 1);
            rig.frames(3);
            int wet = rig.target().rgba(SIZE / 2, SIZE / 2)[1];
            assertThat(wet).as("a coat reflecting the grey sky over the rough grey").isNotEqualTo(dry);
        } finally {
            values.remove("test.tint");
        }
    }

    private static void assertSame(BufferedImage expected, BufferedImage actual, String what) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertThat(actual.getRGB(x, y)).as("%s, pixel %d, %d", what, x, y).isEqualTo(expected.getRGB(x, y));
            }
        }
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

    /** Values by field name, as content holds them: floats per name. */
    private record Values(Map<String, float[]> floats) implements MaterialValues {

        @Override
        public float f32(String name, float fallback) {
            float[] v = floats.get(name);
            return v == null ? fallback : v[0];
        }

        @Override
        public int i32(String name, int fallback) {
            return fallback;
        }

        @Override
        public Vec2 vec2(String name, Vec2 fallback) {
            float[] v = floats.get(name);
            return v == null ? fallback : new Vec2(v[0], v[1]);
        }

        @Override
        public Vec3 vec3(String name, Vec3 fallback) {
            float[] v = floats.get(name);
            return v == null ? fallback : new Vec3(v[0], v[1], v[2]);
        }

        @Override
        public Vec4 vec4(String name, Vec4 fallback) {
            float[] v = floats.get(name);
            return v == null ? fallback : new Vec4(v[0], v[1], v[2], v[3]);
        }

        @Override
        public Color color(String name, Color fallback) {
            float[] v = floats.get(name);
            return v == null ? fallback : new Color(v[0], v[1], v[2], v[3]);
        }

        @Override
        public int texture(String name, boolean srgb, int fallback) {
            return fallback;
        }

        @Override
        public MaterialValues nested(String name) {
            return new Values(Map.of());
        }
    }

    /**
     * A test's technique adding a shading hook to its view, as a library's does ({@link ShadingHooks#add}): its
     * parameters' record from {@code parameters}, read each frame, on while {@code active}.
     */
    private final class Hooked implements Technique {
        private final String hook;
        private volatile ShadingHookParameters parameters;
        private volatile boolean active = true;

        Hooked(String hook, ShadingHookParameters parameters) {
            this.hook = hook;
            this.parameters = parameters;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SHADING_HOOKS);
        }

        @Override
        public void build(GraphBuilder g) {
            ShadingHooks.of(device).add(g, hook, new ShadingHookSource() {
                @Override
                public int bytes() {
                    return parameters.bytes();
                }

                @Override
                public boolean active() {
                    return active;
                }

                @Override
                public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                    parameters.write(into, offset);
                }
            });
        }
    }
}
