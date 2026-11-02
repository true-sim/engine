package com.truesim.engine.render.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
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
import com.truesim.engine.render.Deformation;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleLayout;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferCopy;

/**
 * Mesh, bone and mask shapes (effects.md §2.7, step 15): particles uniform over a mesh's area (a histogram), on its
 * vertices, in its volume, on a skinned copy as posed, along a chain of joints, and thinned by a texture mask. Validated,
 * with synchronization validation, over three frames or more.
 */
class MeshShapesTest {

    private static final Key<Image> PROBE = Key.image("test.meshShapesProbe");
    private static final long FRAME = 16_667;
    private static final AssetRef<?> MASK = AssetRef.of(AssetType.of("test.mask", String.class, s -> 0),
        new AssetId(0, 1));

    private VulkanInstance instance;
    private VulkanDevice device;
    private FrameGraph graph;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private Textures textures;
    private Particles particles;
    private GpuImage target;
    private GpuBuffer poolCopy;
    private GpuBuffer stateCopy;
    private final SceneGraph nodes = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(nodes);
    private final EffectRuntime runtime = new EffectRuntime(effects);

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(256, 16, 8), graph.frameSlots());
        scene = new GpuScene(device, pool, 4, graph.frameSlots());
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        textures = new Textures(device, graph.frameSlots());
        particles = new Particles(device, graph.frameSlots(), 2 << 20, 16).meshes(renderer);
        poolCopy = GpuBuffer.hostVisible(device, particles.pool().buffer().size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        stateCopy = GpuBuffer.hostVisible(device, 4L * ParticleConstants.STATE_WORDS * particles.maxEmitters(),
            VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        target = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        ExternalImage output = new ExternalImage("probe", 4, 4, Format.R8G8B8A8_UNORM, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        graph.add(new ParticleSimulation(particles)).add(new Probe(poolCopy, stateCopy)).output(PROBE, output);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            particles.close();
            poolCopy.close();
            stateCopy.close();
            target.close();
            textures.close();
            renderer.close();
            scene.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Copies the pool and the emitter state into host buffers each frame, after the simulation. */
    private record Probe(GpuBuffer pool, GpuBuffer state) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer particles = g.require(ParticleKeys.PARTICLES);
            Buffer states = g.require(ParticleKeys.STATE);
            Image image = g.create("probe", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("particle probe").read(particles, BufferAccess.TRANSFER_SRC).read(states, BufferAccess.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).sideEffects()
                .record(ctx -> {
                    ctx.clear(image.mip(0), 0, 0, 0, 1);
                    com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    try (var stack = stackPush()) {
                        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                        region.size(pool.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(),
                            ctx.unsafe().buffer(particles), pool.handle(), region);
                        region.size(state.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(states),
                            state.handle(), region);
                    }
                });
            g.provide(PROBE, image);
        }
    }

    /** A frame: the runtime, then the GPU's. */
    private void frame() {
        nodes.update();
        runtime.update(FRAME);
        graph.beginFrame();
        particles.update(effects, runtime.spawns());
        graph.submit(0, 0);
    }

    /**
     * Where the live particles of emitter 0 of {@code effect} were born (x, y, z: they move on at their speed, with no
     * forces), then their velocities.
     */
    private List<float[]> alive(long effect) {
        device.waitIdle();
        int inst = particles.instance(effect, 0);
        int count = stateCopy.memory().getAtIndex(ValueLayout.JAVA_INT,
            (long) inst * ParticleConstants.STATE_WORDS + ParticleConstants.STATE_ALIVE);
        MemorySegment m = poolCopy.memory();
        long base = ((long) particles.currentHalf() * particles.pool().slots() + particles.first(effect, 0))
            * ParticleLayout.BYTES;
        List<float[]> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long p = base + (long) i * ParticleLayout.BYTES;
            int flags = m.get(ValueLayout.JAVA_INT, p + ParticleLayout.FLAGS);
            if ((flags & ParticleConstants.PARTICLE_DEAD) != 0
                || m.get(ValueLayout.JAVA_FLOAT, p + ParticleLayout.AGE)
                >= m.get(ValueLayout.JAVA_FLOAT, p + ParticleLayout.LIFETIME)) {
                continue;
            }
            float[] v = new float[6];
            float age = m.get(ValueLayout.JAVA_FLOAT, p + ParticleLayout.AGE);
            for (int k = 0; k < 3; k++) {
                v[3 + k] = m.get(ValueLayout.JAVA_FLOAT, p + ParticleLayout.VELOCITY + 4L * k);
                v[k] = m.get(ValueLayout.JAVA_FLOAT, p + ParticleLayout.POSITION + 4L * k) - age * v[3 + k];
            }
            list.add(v);
        }
        return list;
    }

    /** Two quads in z = 0 facing +z: 1 x 1 from x = 0 to 1, and 3 x 1 from x = 2 to 5; texture u = x / 5. */
    private static MeshData quads() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 2, 0, 0, 5, 0, 0, 5, 1, 0, 2, 1, 0};
        float[] normals = new float[24];
        float[] uv = new float[16];
        for (int v = 0; v < 8; v++) {
            normals[3 * v + 2] = 1;
            uv[2 * v] = positions[3 * v] / 5;
            uv[2 * v + 1] = positions[3 * v + 1];
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0), 8).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uv)
            .submesh(new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}).buildFlat();
    }

    /** A cube from 0 to 2 along each axis, faces outwards. */
    private static MeshData cube() {
        float[] positions = new float[24];
        for (int v = 0; v < 8; v++) {
            positions[3 * v] = (v & 1) * 2;
            positions[3 * v + 1] = (v >> 1 & 1) * 2;
            positions[3 * v + 2] = (v >> 2 & 1) * 2;
        }
        int[] faces = {0, 2, 3, 0, 3, 1, 4, 5, 7, 4, 7, 6, 0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, 0, 4, 6, 0, 6, 2, 1, 3, 7,
            1, 7, 5};
        return new MeshBuilder(VertexFormat.of(), 8).positions(positions).submesh(faces).buildFlat();
    }

    /** An effect of {@code count} particles at once from {@code shapes}, still, living long. */
    private long play(int count, Module... shapes) {
        Module[] init = java.util.Arrays.copyOf(shapes, shapes.length + 1);
        init[shapes.length] = Init.lifetime(100);
        return effects.play(EffectData.builder().emitter("e", e -> e.spawn(Spawn.burst(0, count)).init(init)
            .init(Init.speed(Bind.constant(1)))).build(), 10, 2, -3).id();
    }

    /** The 3 x 4 rows of a translation to ({@code x}, {@code y}, {@code z}). */
    private static double[] at(double x, double y, double z) {
        return new double[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
    }

    @Test
    void particlesSpreadOverAMeshByItsArea() {
        MeshData data = quads();
        int mesh = pool.add(data);
        long effect = play(4000, Shape.mesh(Shape.SURFACE));
        particles.sources((fx, e) -> particles.emitFrom(fx, e, mesh, data, at(10, 2, -3)));
        for (int f = 0; f < 3; f++) {
            frame();
        }
        List<float[]> all = alive(effect);
        assertThat(all).hasSize(4000);
        int[] bins = new int[6]; // the large quad in half metres
        int small = 0;
        for (float[] p : all) {
            assertThat(p[2]).as("on the plane").isCloseTo(0, within(1e-3f));
            assertThat(p[1]).isBetween(-1e-3f, 1.001f);
            assertThat(p[5]).as("sent along the normal").isCloseTo(1, within(1e-3f));
            if (p[0] < 1.5f) {
                assertThat(p[0]).isBetween(-1e-3f, 1.001f);
                small++;
            } else {
                assertThat(p[0]).isBetween(1.999f, 5.001f);
                bins[Math.min(5, (int) ((p[0] - 2) * 2))]++;
            }
        }
        // A quarter of the area holds a quarter of the particles; the large quad's halves metres as many each
        assertThat(small / 4000.0).isCloseTo(0.25, within(0.03));
        for (int bin : bins) {
            assertThat(bin / 3000.0).isCloseTo(1 / 6.0, within(0.03));
        }
        assertThat(particles.meshTableCount()).isOne();
    }

    @Test
    void verticesAndVolumesAreShapesToo() {
        MeshData flat = quads();
        int quadMesh = pool.add(flat);
        MeshData box = cube();
        int cubeMesh = pool.add(box);
        long onVertices = play(400, Shape.mesh(Shape.VERTICES));
        long inside = play(3000, Shape.mesh(Shape.VOLUME));
        long nowhere = play(100, Shape.mesh(Shape.SURFACE)); // no mesh named: born dead
        particles.sources((fx, e) -> {
            if (fx == onVertices) {
                particles.emitFrom(fx, e, quadMesh, flat, at(10, 2, -3));
            } else if (fx == inside) {
                particles.emitFrom(fx, e, cubeMesh, box, at(9, 1, -4)); // the cube about the effect's position
            } else {
                particles.emitFrom(fx, e, -1, null, null);
            }
        });
        for (int f = 0; f < 3; f++) {
            frame();
        }
        for (float[] p : alive(onVertices)) {
            boolean corner = false;
            for (int v = 0; v < 8; v++) {
                double vx = v < 4 ? (v == 1 || v == 2 ? 1 : 0) : (v == 5 || v == 6 ? 5 : 2);
                double vy = v % 4 >= 2 ? 1 : 0;
                corner |= Math.abs(p[0] - vx) < 1e-3 && Math.abs(p[1] - vy) < 1e-3;
            }
            assertThat(corner).as("on a vertex: " + p[0] + ", " + p[1]).isTrue();
        }
        List<float[]> in = alive(inside);
        assertThat(in).hasSize(3000);
        int below = 0;
        double sum = 0;
        for (float[] p : in) {
            for (int k = 0; k < 3; k++) {
                assertThat(p[k]).as("in the cube").isBetween(-1.001f, 1.001f);
            }
            below += p[1] < 0 ? 1 : 0;
            sum += p[0];
        }
        assertThat(below / 3000.0).as("uniform: half below its middle").isCloseTo(0.5, within(0.04));
        assertThat(sum / 3000).isCloseTo(0, within(0.05));
        assertThat(alive(nowhere)).as("no mesh: born dead").isEmpty();
        assertThat(particles.meshTableCount()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> particles.emitFrom(12345, 0, quadMesh, flat, at(0, 0,
            0)));
        assertThatIllegalArgumentException().isThrownBy(() -> particles.joints(onVertices, 0, new double[3], 17));
    }

    @Test
    void skinnedCopiesEmitWhereTheyArePosed() {
        // One joint lifting the whole copy by 2 and moving it 3 along x: particles on the posed quad, not the rest one
        float[] positions = {-1, 0, -1, 1, 0, -1, -1, 0, 1, 1, 0, 1};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] weights = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        MeshData data = new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.JOINTS,
            VertexAttribute.WEIGHTS), 4).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.WEIGHTS, weights).joints(new int[16]).submesh(new int[] {0, 2, 1, 1, 2, 3})
            .buildFlat();
        int source = pool.add(data);
        int copy = pool.addDeformed(source);
        Deformation deformation = pool.deformation();
        deformation.add(copy, 1);
        deformation.joint(copy, 0, 1, 0, 0, 3, 0, 1, 0, 2, 0, 0, 1, 0);
        deformation.bounds(copy, 2, 2, -1, 4, 2, 1, 3.7);
        long effect = play(500, Shape.mesh(Shape.SURFACE));
        particles.sources((fx, e) -> particles.emitFrom(fx, e, copy, data, at(10, 2, -3)));
        for (int f = 0; f < 4; f++) {
            frame();
        }
        List<float[]> all = alive(effect);
        assertThat(all).hasSize(500);
        for (float[] p : all) {
            assertThat(p[0]).isBetween(1.99f, 4.01f);
            assertThat(p[1]).isCloseTo(2, within(1e-3f));
            assertThat(p[2]).isBetween(-1.01f, 1.01f);
            assertThat(p[4]).as("along the posed normal").isCloseTo(1, within(1e-2f));
        }
    }

    @Test
    void boneShapesRunAlongTheirJoints() {
        long effect = play(1000, Shape.bones(0));
        long thick = play(1000, Shape.bones(0.25f));
        long none = play(10, Shape.bones(0.1f)); // no joints: born dead
        double[] chain = {10, 2, -3, 10, 4, -3, 12, 4, -3}; // up 2, then along x 2
        particles.sources((fx, e) -> particles.joints(fx, e, chain, fx == none ? 0 : 3));
        for (int f = 0; f < 3; f++) {
            frame();
        }
        int up = 0;
        for (float[] p : alive(effect)) {
            boolean onFirst = Math.abs(p[0]) < 1e-3 && p[1] > -1e-3 && p[1] < 2.001;
            boolean onSecond = Math.abs(p[1] - 2) < 1e-3 && p[0] > -1e-3 && p[0] < 2.001;
            assertThat(onFirst || onSecond).as(p[0] + ", " + p[1]).isTrue();
            assertThat(p[2]).isCloseTo(0, within(1e-3f));
            up += onFirst && p[1] < 1.999 ? 1 : 0;
        }
        assertThat(up / 1000.0).as("by length: half on each").isCloseTo(0.5, within(0.05));
        for (float[] p : alive(thick)) {
            // From the nearer segment: (0, 0..2, 0), then (0..2, 2, 0)
            double y = Math.max(0, Math.min(2, p[1]));
            double x = Math.max(0, Math.min(2, p[0]));
            double first = Math.sqrt(p[0] * p[0] + (p[1] - y) * (p[1] - y) + p[2] * p[2]);
            double second = Math.sqrt((p[0] - x) * (p[0] - x) + (p[1] - 2) * (p[1] - 2) + p[2] * p[2]);
            double across = Math.min(first, second);
            assertThat(across).isLessThan(0.2501);
        }
        assertThat(alive(none)).isEmpty();
    }

    @Test
    void masksThinTheirShapes() {
        // Left half black, right half white: a threshold keeps the right half's particles, a density thins by it
        int slot = textures.color(2, 1, MemorySegment.ofArray(new int[] {0xff000000, 0xffffffff}));
        particles.texture(MASK, slot);
        MeshData data = quads();
        int mesh = pool.add(data);
        long cut = play(2000, Shape.mesh(Shape.SURFACE), Shape.mask(MASK, 0, 0.5f, 1, 1));
        long projected = play(2000, Shape.disc(1), Shape.mask(MASK));
        particles.sources((fx, e) -> particles.emitFrom(fx, e, mesh, data, at(10, 2, -3)));
        for (int f = 0; f < 3; f++) {
            frame();
        }
        List<float[]> kept = alive(cut);
        assertThat(kept).isNotEmpty();
        for (float[] p : kept) {
            assertThat(p[0] / 5).as("u at least a half").isGreaterThanOrEqualTo(0.45f);
        }
        List<float[]> thinned = alive(projected);
        int right = 0;
        for (float[] p : thinned) {
            right += p[0] > 0 ? 1 : 0;
        }
        assertThat(thinned.size()).as("about half kept").isBetween(800, 1200);
        assertThat(right).as("the white half's").isGreaterThan(thinned.size() * 3 / 4);
    }
}
