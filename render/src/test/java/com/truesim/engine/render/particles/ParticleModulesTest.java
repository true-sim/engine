package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleLayout;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.InvalidEffectException;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.ModuleType;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.effects.Target;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * A game's own particle modules (effects step 20), as {@code CustomPassTest} does for passes: a force and an
 * initializer in a kernel variant of the test's (test/particlegust.slang), registered with {@link ParticleModules}, and
 * a technique of the game's modifying the particles ({@code modify(PARTICLES)}) after the simulation, before anything
 * reads them.
 */
class ParticleModulesTest {

    private static final long FRAME = 16_667;
    private static final Key<Image> PROBE = Key.image("test.moduleProbe");
    static final ModuleType GUST = ModuleType.builder("game.gust", ModuleType.Stage.UPDATE).targets(Target.GPU)
        .field("strength", Bind.constant(1)).build();
    static final ModuleType TINT = ModuleType.builder("game.tint", ModuleType.Stage.INIT).targets(Target.GPU)
        .field("blue", Bind.constant(1)).build();

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private Particles particles;
    private GpuBuffer poolCopy;
    private GpuImage target;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final ParticleModules modules = new ParticleModules("shaders/test/particlegust.spv", List.of(GUST, TINT));

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        graph = new FrameGraph(device, 2);
        particles = new Particles(device, graph.frameSlots(), 1 << 20, 16).modules(modules);
        poolCopy = GpuBuffer.hostVisible(device, particles.pool().buffer().size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        graph.add(new ParticleSimulation(particles)).add(new Probe(poolCopy)).output(PROBE, new ExternalImage("probe",
            4, 4, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .set(target.handle(), target.view()));
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            particles.close();
            poolCopy.close();
            target.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Copies the pool into a host buffer each frame, after the simulation and whatever modifies the particles. */
    private record Probe(GpuBuffer pool) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer particles = g.require(ParticleKeys.PARTICLES);
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("module probe").read(particles, BufferAccess.TRANSFER_SRC).write(image, Access.TRANSFER_DST)
                .sideEffects().record(ctx -> {
                    ctx.clear(image.mip(0), 0, 0, 0, 1);
                    // Every frame copies into the same buffer: after the frames before
                    com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    try (var stack = stackPush()) {
                        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).size(pool.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(particles),
                            pool.handle(), region);
                    }
                });
            g.provide(PROBE, image);
        }
    }

    /** A technique of the game's: sets the first particle's alpha after the simulation (it modifies the particles). */
    private record Fade(Particles particles) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(ParticleKeys.PARTICLES);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer pool = g.modify(ParticleKeys.PARTICLES);
            g.pass("game particle fade").write(pool, BufferAccess.TRANSFER_DST).record(ctx -> {
                // The first particle of the first range of the half written: its alpha a quarter, by a fill
                long at = (long) particles.currentHalf() * particles.pool().slots() * ParticleLayout.BYTES
                    + ParticleLayout.COLOR + 12;
                org.lwjgl.vulkan.VK10.vkCmdFillBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(pool), at, 4,
                    Float.floatToRawIntBits(0.25f));
            });
            g.provide(ParticleKeys.PARTICLES, pool);
        }
    }

    private void frames(int n) {
        for (int f = 0; f < n; f++) {
            runtime.update(FRAME);
            graph.beginFrame();
            particles.update(runtime, null);
            graph.submit(0, 0);
        }
        device.waitIdle();
    }

    /** Float {@code field} of particle {@code i} of the range at slot {@code first} of the half written last. */
    private float read(int first, int i, long field) {
        MemorySegment pool = poolCopy.memory();
        long at = ((long) particles.currentHalf() * particles.pool().slots() + first + i) * ParticleLayout.BYTES;
        return pool.get(ValueLayout.JAVA_FLOAT, at + field);
    }

    @Test
    void aGamesOwnModulesPushAndTintItsParticles() {
        EffectData data = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 4))
            .init(Init.lifetime(100), Module.of("game.tint").with("blue", Bind.constant(0.5f)))
            .update(Module.of("game.gust").with("strength", Bind.constant(2)))).build(modules.registry());
        long effect = effects.play(data, 0, 0, 0).id();
        frames(30);
        int first = particles.first(effect, 0);
        double t = 30 * FRAME * 1e-6;
        // A constant push of 2 m/s^2 for its age: x = a t^2 / 2, v = a t
        assertThat((double) read(first, 0, ParticleLayout.POSITION)).isCloseTo(t * t, within(0.02));
        assertThat((double) read(first, 0, ParticleLayout.VELOCITY)).isCloseTo(2 * t, within(0.02));
        assertThat(read(first, 0, ParticleLayout.COLOR + 8)).as("tinted at birth").isEqualTo(0.5f);
        assertThat(read(first, 0, ParticleLayout.COLOR)).isZero();
        assertThat(modules.registry().type("game.gust")).isEqualTo(GUST);
        assertThat(modules.indexOf("game.tint")).isEqualTo(1);
        assertThat(modules.indexOf("nothing")).isEqualTo(-1);
    }

    @Test
    void effectsNamingThemNeedTheirRegistryAndModulesTheirFieldsFew() {
        assertThatThrownBy(() -> EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 1))
            .update(Module.of("game.gust"))).build()).isInstanceOf(InvalidEffectException.class);
        ModuleType.Builder many = ModuleType.builder("game.many", ModuleType.Stage.UPDATE);
        for (int f = 0; f <= ParticleModules.MAX_FIELDS; f++) {
            many.field("f" + f, Bind.constant(0));
        }
        ModuleType tooMany = many.build();
        assertThatIllegalArgumentException().isThrownBy(() -> new ParticleModules("x.spv", List.of(tooMany)));
        assertThat(particles.modules(null)).isSameAs(particles);
    }

    @Test
    void aGamesTechniqueModifiesTheParticlesAfterTheSimulation() {
        graph.add(new Fade(particles));
        EffectData data = EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, 2)).init(Init.lifetime(100)))
            .build(modules.registry());
        long effect = effects.play(data, 0, 0, 0).id();
        frames(4);
        int first = particles.first(effect, 0);
        assertThat(read(first, 0, ParticleLayout.COLOR + 12)).as("modified").isEqualTo(0.25f);
        assertThat(read(first, 1, ParticleLayout.COLOR + 12)).as("as simulated").isEqualTo(1);
        assertThat(ParticleConstants.OP_PLUGIN).isEqualTo(64);
    }
}
