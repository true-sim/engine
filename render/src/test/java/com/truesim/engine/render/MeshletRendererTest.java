package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.engine.render.layout.ViewLayout;
import com.truesim.common.math.color.Color;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/** The GPU-driven meshlet path, on the GPU: what is drawn, what is culled, and detail that keeps the surface whole. */
class MeshletRendererTest {

    private static final int SIZE = 128;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final MetallicRoughnessParameters GREEN =
        MR.withBaseColor(new Color(0.1, 0.8, 0.1, 1)).withRoughness(1);
    private static final int WHITE = 0xffffffff;
    private static final float HALF = (float) Math.sqrt(0.5);

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private GpuImage color;
    private GpuImage depth;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private int quad;
    private int terrain;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        if ("true".equals(System.getenv("TRUESIM_REQUIRE_VALIDATION")) && !instance.validationEnabled()) {
            throw new AssertionError("Validation is required (TRUESIM_REQUIRE_VALIDATION) but the layer is not active");
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 16, 1024, 8));
        quad = pool.add(grid(1, 2, false, VertexFormat.POSITIONS_ONLY));
        terrain = pool.add(grid(64, 1, true, VertexFormat.of(VertexAttribute.NORMAL)));
        scene = new GpuScene(device, pool, 256);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(8192, 1 << 20));
        color = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        depth = new GpuImage(device, SIZE, SIZE, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
            VK_IMAGE_ASPECT_DEPTH_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            int errors = instance.validationErrors();
            pixels.close();
            depth.close();
            color.close();
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

    /**
     * A grid of {@code n} x {@code n} quads, {@code size} units each, in the x-z plane facing +y, centered on the origin;
     * bumpy (height 0.6 sin(x / 3) cos(z / 4)) when asked, built as a cluster hierarchy.
     */
    static MeshData grid(int n, float size, boolean bumpy, VertexFormat format) {
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        float[] normals = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                positions[3 * v] = (x - n / 2f) * size;
                positions[3 * v + 1] = bumpy ? (float) (0.6 * Math.sin(x / 3.0) * Math.cos(z / 4.0)) : 0;
                positions[3 * v + 2] = (z - n / 2f) * size;
                normals[3 * v + 1] = 1;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int[] quad = {a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        MeshBuilder builder = new MeshBuilder(format, count).positions(positions).submesh(indices);
        if (format.has(VertexAttribute.NORMAL)) {
            builder.attribute(VertexAttribute.NORMAL, normals);
        }
        return bumpy ? builder.buildHierarchy() : builder.buildFlat();
    }

    /** Renders a frame and reads it back; returns the visible meshlets. */
    private int render() {
        renderWith(commands -> renderer.record(commands, view, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
        return view.visibleMeshlets();
    }

    /** Records {@code draw} into the color and depth images and reads the color back. */
    private void renderWith(java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> draw) {
        device.submitAndWait(device.graphics(), commands -> {
            Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            draw.accept(commands);
            Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, color.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
    }

    private int pixel(int x, int y) {
        return pixels.memory().get(ValueLayout.JAVA_INT, (y * SIZE + x) * 4L);
    }

    /** How many pixels are drawn (not the black clear color). */
    private int covered() {
        int count = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            if ((pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i) & 0xffffff) != 0) {
                count++;
            }
        }
        return count;
    }

    /** Looks down -Z with a 60 degree field of view. */
    private void lookForward(float errorPixels, int mode) {
        view.set(camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), errorPixels, mode);
    }

    @Test
    void aQuadFacingTheCameraCoversWhereItShould() {
        // The quad faces +y; rotated 90 degrees about x it faces +z, the camera, 5 units ahead.
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        scene.removeFrom(1);
        lookForward(1, MeshletRenderer.MODE_WHITE);
        assertThat(render()).isEqualTo(1);
        // 2 units across at distance 5 with a 60 degree view: 128 / (2 * 5 * tan 30) * 2 = 44 pixels across.
        assertThat(pixel(SIZE / 2, SIZE / 2)).isEqualTo(WHITE);
        assertThat(pixel(SIZE / 2 + 20, SIZE / 2 - 20)).isEqualTo(WHITE);
        assertThat(pixel(SIZE / 2 + 24, SIZE / 2) & 0xffffff).isZero(); // the clear color, black
        assertThat(pixel(2, 2) & 0xffffff).isZero();
        assertThat(covered()).isCloseTo(44 * 44, org.assertj.core.data.Percentage.withPercentage(10));
    }

    @Test
    void meshletsOutsideTheViewOrFacingAwayAreNotDrawn() {
        scene.set(0, quad, 0, 0, 5, HALF, 0, 0, HALF, 1, 1, 1);       // behind the camera
        scene.set(1, quad, 40, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);     // far to the right
        scene.set(2, quad, 0, 0, -5, -HALF, 0, 0, HALF, 1, 1, 1);     // facing away
        scene.removeFrom(3);
        lookForward(1, MeshletRenderer.MODE_WHITE);
        assertThat(render()).isZero();
        assertThat(covered()).isZero();
        scene.removeFrom(0);
        assertThat(render()).isZero();
    }

    @Test
    void detailFollowsTheAllowedErrorWithoutHoles() {
        // The bumpy terrain, 64 x 64 units, seen from 40 units above and in front, looking down at 45 degrees.
        scene.set(0, terrain, 0, -40, -40, 0, 0, 0, 1, 1, 1, 1);
        scene.removeFrom(1);
        int previous = Integer.MAX_VALUE;
        int full = -1;
        int fullCoverage = -1;
        for (float error : new float[] {0, 0.5f, 2, 8, 32, 1e6f}) {
            view.set(camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), error,
                MeshletRenderer.MODE_WHITE);
            int meshlets = render();
            assertThat(meshlets).as("meshlets at %s pixels", error).isLessThanOrEqualTo(previous).isPositive();
            previous = meshlets;
            full = full < 0 ? meshlets : full;
            int coverage = covered();
            if (fullCoverage < 0) {
                fullCoverage = coverage;
            }
            // Coarser detail keeps the surface: the covered area hardly changes (silhouette pixels only).
            assertThat((double) Math.abs(coverage - fullCoverage) / fullCoverage).as("coverage at %s pixels", error)
                .isLessThan(0.02);
        }
        assertThat(previous).isLessThan(full / 4); // the coarsest cut: a few meshlets
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        assertThat(render()).isPositive();
        view.set(camera, 1, MeshletRenderer.MODE_MESHLETS);
        assertThat(render()).isPositive();
    }

    @Test
    void manyInstancesDrawInOnePass() {
        int n = 0;
        for (int x = -7; x <= 7; x++) {
            for (int y = -7; y <= 7; y++) {
                scene.set(n++, quad, x * 3, y * 3, -60, HALF, 0, 0, HALF, 1, 1, 1);
            }
        }
        scene.removeFrom(n);
        lookForward(1, MeshletRenderer.MODE_WHITE);
        assertThat(render()).isEqualTo(n); // all 225 in view at 60 units
        assertThat(view.options().draws()).isEqualTo(8192);
    }

    @Test
    void bothPathsDrawTheSameImage() {
        assumeThat(device.meshShaders()).as("no mesh shaders").isTrue();
        assertThat(renderer.path()).isEqualTo(MeshletRenderer.Path.MESH_SHADERS); // chosen where available
        scene.set(0, terrain, 0, -40, -40, 0, 0, 0, 1, 1, 1, 1);
        int n = 1;
        for (int i = 0; i < 20; i++) {
            scene.set(n++, quad, (i % 5 - 2) * 4, (i / 5 - 2) * 3, -15 - i, HALF, 0, 0, HALF, 1, 1, 1);
        }
        scene.removeFrom(n);
        view.set(camera.set(0, -0.3, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1,
            MeshletRenderer.MODE_MESHLETS);
        renderer.setPath(MeshletRenderer.Path.INDEXED);
        assertThat(renderer.path()).isEqualTo(MeshletRenderer.Path.INDEXED);
        int indexed = render();
        int[] image = new int[SIZE * SIZE];
        for (int i = 0; i < image.length; i++) {
            image[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        renderer.setPath(MeshletRenderer.Path.MESH_SHADERS);
        assertThat(render()).as("visible meshlets").isEqualTo(indexed).isPositive();
        int different = 0;
        for (int i = 0; i < image.length; i++) {
            if (pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i) != image[i]) {
                different++;
            }
        }
        assertThat(different).as("pixels that differ").isLessThanOrEqualTo(image.length / 1000);
        assertThat(covered()).isGreaterThan(image.length / 4);
        renderer.setPath(MeshletRenderer.Path.AUTO);
        assertThat(renderer.path()).isEqualTo(MeshletRenderer.Path.MESH_SHADERS);
    }

    @Test
    void viewsCulledTogetherDrawWhatEachSees() {
        scene.set(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1); // ahead, facing the camera
        scene.set(1, quad, 0, 0, 5, -HALF, 0, 0, HALF, 1, 1, 1); // behind, facing it
        lookForward(1, MeshletRenderer.MODE_WHITE);
        RenderView back = renderer.createView(ViewOptions.of(64, 1 << 12))
            .set(new Camera().set(0, 0, 1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1, MeshletRenderer.MODE_WHITE);
        RenderView[] both = {view, back};
        boolean[] notEarly = {false, false};
        assertThat(render()).isEqualTo(1);
        int alone = covered();
        for (MeshletRenderer.Path path : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED,
            MeshletRenderer.Path.AUTO}) {
            renderer.setPath(path);
            renderWith(commands -> {
                renderer.cullViews(commands, both, notEarly, 2);
                renderer.drawView(commands, view, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0);
            });
            assertThat(covered()).isEqualTo(alone);
            assertThat(view.visibleMeshlets()).isEqualTo(1);
            assertThat(back.visibleMeshlets()).isEqualTo(1); // each its own quad
            renderWith(commands -> {
                renderer.cullViews(commands, both, notEarly, 2);
                renderer.drawView(commands, back, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0);
            });
            assertThat(covered()).isEqualTo(alone); // the other quad, from the other side
        }
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.cullViews(null, both, notEarly, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.cullViews(null, both, notEarly, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.cullViews(null, both, new boolean[1], 2));
        assertThatIllegalArgumentException().as("the early phase without occlusion")
            .isThrownBy(() -> renderer.cullViews(null, both, new boolean[] {true, false}, 2));
        assertThatIllegalArgumentException().as("culled twice")
            .isThrownBy(() -> renderer.cullViews(null, new RenderView[] {back, back}, notEarly, 2));
        assertThatIllegalArgumentException().as("the late phase without occlusion")
            .isThrownBy(() -> renderer.recordLate(null, view, 0, 0, 1, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> ViewOptions.of(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> ViewOptions.of((1 << 22) + 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> ViewOptions.of(1, 0));
        try (GpuScene other = new GpuScene(device, pool, 4);
             MeshletRenderer elsewhere = new MeshletRenderer(device, other, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT)) {
            RenderView theirs = elsewhere.createView(ViewOptions.of(16, 64));
            assertThat(elsewhere.scene()).isSameAs(other);
            assertThat(elsewhere.views()).containsExactly(theirs);
            assertThatIllegalArgumentException().as("another renderer's view")
                .isThrownBy(() -> renderer.drawView(null, theirs, 0, 0, 1, 1, 0, 0, 0));
            assertThatIllegalArgumentException().as("another renderer's view")
                .isThrownBy(() -> renderer.cullViews(null, new RenderView[] {theirs}, notEarly, 1));
            elsewhere.close();
            assertThatIllegalStateException().as("closed with its renderer")
                .isThrownBy(() -> theirs.set(camera, 1, 0));
            assertThatIllegalStateException().isThrownBy(() -> theirs.filter(-1, false));
        }
    }

    @Test
    void holesLayersAndCastersDecideWhatAViewDraws() {
        // Three quads side by side, facing the camera, one meshlet each.
        for (int i = 0; i < 3; i++) {
            scene.set(i, quad, 0, 2 * i - 2, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f);
        }
        scene.removeFrom(3);
        lookForward(1, MeshletRenderer.MODE_WHITE);
        assertThat(render()).isEqualTo(3);
        // A removed instance is a hole: skipped, the others keep their ids.
        scene.remove(1);
        assertThat(render()).isEqualTo(2);
        scene.set(1, quad, 0, 0, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f);
        assertThat(render()).isEqualTo(3);
        // Layers: the right quad on layer 3 only, the left one casting no shadow.
        scene.set(2, quad, 0, 1 << 3, true, 2, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f);
        scene.set(0, quad, 0, 1, false, -2, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f);
        assertThat(render()).as("every layer, by default").isEqualTo(3);
        view.filter(1, false);
        assertThat(render()).as("layer 0").isEqualTo(2);
        view.filter(1 << 3 | 1 << 7, false);
        assertThat(render()).as("layers 3 and 7").isEqualTo(1);
        assertThat(pixel(SIZE / 2 + 30, SIZE / 2)).isEqualTo(WHITE); // the right one
        view.filter(-1, true);
        assertThat(render()).as("casters only").isEqualTo(2);
        assertThat(pixel(SIZE / 2 - 30, SIZE / 2) & 0xffffff).isZero(); // not the left one
        view.filter(-1, false);
        org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException()
            .isThrownBy(() -> scene.remove(-1));
    }

    @Test
    void maskedAndDoubleSidedMaterialsDrawInBinsOfTheirOwn() {
        try (Textures textures = new Textures(device); Materials materials = new Materials(device, 16)) {
            // A checker of opaque and see-through texels, 2 x 2 each.
            int checker = textures.color(8, 8, texels(8, (x, y) -> ((x / 2 + y / 2) % 2 == 0) ? 0xffffffff
                : 0x00ffffff));
            // The textured quad has two submeshes: each material twice, one per half.
            int plain = materials.addAll(java.util.List.of(Material.DEFAULT, Material.DEFAULT));
            Material doubleSided = Material.DEFAULT.doubleSided(true);
            int twoSided = materials.addAll(java.util.List.of(doubleSided, doubleSided));
            Material checkered = new Material(MR.withBaseColorTexture(checker)).alphaMask(0.5f);
            int masked = materials.addAll(java.util.List.of(checkered, checkered));
            Material checkeredTwoSided = new Material(MR.withBaseColorTexture(checker)).alphaMask(0.5f).doubleSided(true);
            int maskedTwoSided = materials.addAll(java.util.List.of(checkeredTwoSided, checkeredTwoSided));
            assertThat(Material.DEFAULT.masked()).isFalse();
            int textured = pool.add(texturedQuad());
            scene.setMaterials(materials);
            lookForward(1, MeshletRenderer.MODE_WHITE);
            for (MeshletRenderer.Path path : device.meshShaders()
                ? new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS}
                : new MeshletRenderer.Path[] {MeshletRenderer.Path.INDEXED}) {
                renderer.setPath(path);
                // One quad at a time in front of the camera, 5 units ahead: facing it, or facing away.
                scene.set(0, textured, plain, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1); // facing +z: the camera
                int front = render() > 0 ? covered() : 0;
                assertThat(front).as("the front, %s", path).isCloseTo(44 * 44,
                    org.assertj.core.data.Percentage.withPercentage(10));
                assertThat(view.visibleMeshlets(0)).isPositive();
                scene.set(0, textured, plain, 0, 0, -5, 0, 1, 0, 0, 1, 1, 1); // turned about y: facing away
                render();
                assertThat(covered()).as("one-sided, facing away: culled, %s", path).isZero();
                scene.set(0, textured, twoSided, 0, 0, -5, 0, 1, 0, 0, 1, 1, 1);
                render();
                assertThat(view.visibleMeshlets(1)).as("double-sided, facing away: drawn, %s", path).isPositive();
                assertThat(covered()).isEqualTo(front);
                // Lit as the front: the back face's normal turned to the viewer.
                lookForward(1, MeshletRenderer.MODE_LIT);
                render();
                int back = pixel(SIZE / 2 - 8, SIZE / 2);
                scene.set(0, textured, twoSided, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1);
                render();
                int lit = pixel(SIZE / 2 + 8, SIZE / 2); // the same point of the surface, seen from the front
                assertThat(back & 0xff).as("the back face's red, against the front's %d, %s", lit & 0xff, path)
                    .isCloseTo(lit & 0xff, org.assertj.core.data.Offset.offset(8));
                lookForward(1, MeshletRenderer.MODE_WHITE);
                scene.set(0, textured, masked, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1);
                render();
                assertThat(view.visibleMeshlets(2)).as("masked, %s", path).isPositive();
                assertThat((double) covered() / front).as("half the texels masked, %s", path)
                    .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.1));
                scene.set(0, textured, masked, 0, 0, -5, 0, 1, 0, 0, 1, 1, 1);
                render();
                assertThat(covered()).as("masked one-sided back faces: not drawn, %s", path).isZero();
                scene.set(0, textured, maskedTwoSided, 0, 0, -5, 0, 1, 0, 0, 1, 1, 1);
                render();
                assertThat((double) covered() / front).as("masked double-sided, facing away, %s", path)
                    .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.1));
            }
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Material.DEFAULT.alphaMask(1.5f));
        assertThat(Material.DEFAULT.alphaMask(0.3f).opaque().masked()).isFalse();
        assertThat(Material.DEFAULT.doubleSided(true).doubleSided()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> ViewOptions.of(1, 1).withBins(0, 1));
    }

    @Test
    void bothPathsShadeTheSameImage() {
        assumeThat(device.meshShaders()).as("mesh shaders").isTrue();
        try (Textures textures = new Textures(device); Materials materials = new Materials(device, 16)) {
            // A texture of smooth gradients, on a quad turned away a little and lit: positions, normals and texture
            // coordinates all interpolated, as the indexed path's hardware does and the slim mesh path rebuilds.
            int gradient = textures.color(16, 16, texels(16, (x, y) -> 0xff000000 | (x * 16) | (y * 16) << 8));
            Material material = new Material(MR.withBaseColorTexture(gradient));
            int textured = materials.addAll(java.util.List.of(material, material));
            int mesh = pool.add(texturedQuad());
            scene.setMaterials(materials);
            scene.set(0, mesh, textured, 0.3, -0.2, -4, 0.2f, 0.3f, 0, 0.93f, 1.5f, 1.5f, 1.5f);
            lookForward(1, MeshletRenderer.MODE_LIT);
            int[][] images = new int[2][SIZE * SIZE];
            MeshletRenderer.Path[] paths = {MeshletRenderer.Path.INDEXED, MeshletRenderer.Path.MESH_SHADERS};
            for (int p = 0; p < paths.length; p++) {
                renderer.setPath(paths[p]);
                render();
                for (int i = 0; i < SIZE * SIZE; i++) {
                    images[p][i] = pixel(i % SIZE, i / SIZE);
                }
            }
            int covered = 0;
            for (int i = 0; i < SIZE * SIZE; i++) {
                covered += images[0][i] != images[0][0] ? 1 : 0;
                for (int shift = 0; shift < 24; shift += 8) {
                    assertThat((images[1][i] >> shift) & 0xff).as("pixel %d, channel %d", i, shift / 8)
                        .isCloseTo((images[0][i] >> shift) & 0xff, org.assertj.core.data.Offset.offset(2));
                }
            }
            assertThat(covered).as("the quad covers the middle").isGreaterThan(SIZE * SIZE / 8);
            renderer.setPath(MeshletRenderer.Path.AUTO);
            scene.setMaterials(null);
        }
    }

    @Test
    void viewsReportWhatDidNotFitAndResize() {
        // Three quads, a meshlet and 2 triangles each, into a view with room for two meshlets.
        for (int i = 0; i < 3; i++) {
            scene.set(i, quad, 0, 2 * i - 2, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f);
        }
        lookForward(1, MeshletRenderer.MODE_WHITE);
        RenderView small = renderer.createView(ViewOptions.of(2, 1 << 12)).set(camera, 1, MeshletRenderer.MODE_WHITE);
        renderWith(commands -> renderer.record(commands, small, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
        assertThat(small.visibleMeshlets()).as("found, if not all drawn").isEqualTo(3);
        assertThat(small.overflowed()).isTrue();
        int two = covered();
        long bytes = small.bytes();
        small.resize(8, 1 << 12);
        assertThat(small.options().draws()).isEqualTo(8);
        assertThat(small.bytes()).isGreaterThan(bytes);
        renderWith(commands -> renderer.record(commands, small, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
        assertThat(small.overflowed()).isFalse();
        assertThat(covered()).as("the third quad drawn too").isGreaterThan(two);
        assertThat(small.wantedTriangles()).isEqualTo(6);
        // The indexed path: an index buffer per view, its triangles the limit.
        renderer.setPath(MeshletRenderer.Path.INDEXED);
        small.resize(8, 4);
        renderWith(commands -> renderer.record(commands, small, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
        assertThat(small.hasIndices()).isTrue();
        assertThat(small.wantedTriangles()).isEqualTo(6);
        assertThat(small.overflowed()).as("6 triangles wanted, room for 4").isTrue();
        small.resize(8, 6);
        renderWith(commands -> renderer.record(commands, small, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
        assertThat(small.overflowed()).isFalse();
        assertThat(renderer.viewBytes()).isEqualTo(view.bytes() + small.bytes());
        if (device.meshShaders()) {
            long indexed = small.bytes();
            renderer.setPath(MeshletRenderer.Path.MESH_SHADERS);
            assertThat(small.hasIndices()).as("mesh shaders need none").isFalse();
            assertThat(small.bytes()).as("the index buffer, every bin's").isEqualTo(indexed
                - 12L * small.options().allTriangles());
            assertThat(renderer.createView(ViewOptions.of(8, 1 << 20)).hasIndices()).isFalse();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> small.resize(0, 1));
    }

    @Test
    void instancesTakeTheLowestFreeId() {
        try (GpuScene small = new GpuScene(device, pool, 3)) {
            assertThat(small.pool()).isSameAs(pool);
            assertThat(small.capacity()).isEqualTo(3);
            assertThat(small.materials()).isNotNull();
            for (int i = 0; i < 3; i++) {
                assertThat(small.freeId()).isEqualTo(i);
                assertThat(small.add(quad, 0, 1, true, i, 0, -5, 0, 0, 0, 1, 1, 1, 1)).isEqualTo(i);
            }
            assertThat(small.add(quad, 0, 1, true, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1)).as("full").isEqualTo(-1);
            assertThat(small.freeId()).isEqualTo(-1);
            assertThat(small.count()).isEqualTo(3);
            assertThat(small.highWater()).isEqualTo(3);
            small.remove(1);
            small.remove(1); // already a hole
            assertThat(small.contains(1)).isFalse();
            assertThat(small.contains(-1)).isFalse();
            assertThat(small.contains(3)).isFalse();
            assertThat(small.highWater()).as("a hole below the highest").isEqualTo(3);
            assertThat(small.add(quad, 0, 1, true, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1)).as("the hole").isEqualTo(1);
            small.set(1, quad, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1); // set again: still one instance
            assertThat(small.count()).isEqualTo(3);
            small.removeFrom(1);
            assertThat(small.count()).isEqualTo(1);
            assertThat(small.highWater()).isEqualTo(1);
            small.remove(0);
            assertThat(small.highWater()).isZero();
            assertThatIllegalArgumentException().isThrownBy(() -> small.set(0, 99, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> small.set(0, quad, -1, 0f, 0, 0, 0, 0, 0, 1, 1, 1,
                1));
            assertThatIllegalArgumentException().isThrownBy(() -> small.set(3, quad, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1));
            small.close();
            small.close();
        }
    }

    @Test
    void changesUploadOncePerIdAndScatterOnTheGpu() {
        com.truesim.engine.vulkan.FrameSlots slots = new com.truesim.engine.vulkan.FrameSlots(2);
        try (GpuScene small = new GpuScene(device, pool, 3, slots);
             MeshletRenderer drawing = new MeshletRenderer(device, small, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots)) {
            RenderView ahead = drawing.createView(ViewOptions.of(64, 1 << 12));
            for (int i = 0; i < 3; i++) {
                small.set(i, quad, 0, (i - 1) * 2.5f, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f);
            }
            small.set(1, quad, 0, 0, 0, -8, HALF, 0, 0, HALF, 0.8f, 0.8f, 0.8f); // again: the same entry
            assertThat(small.pendingCount()).isEqualTo(3);
            slots.begin(0);
            lookForward(1, MeshletRenderer.MODE_WHITE);
            ahead.set(camera, 1, MeshletRenderer.MODE_WHITE);
            renderWith(commands -> drawing.record(commands, ahead, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
            slots.end();
            assertThat(small.pendingCount()).as("scattered").isZero();
            assertThat(ahead.visibleMeshlets()).isEqualTo(3);
            // A frame changing every instance twice between its passes: more than the upload buffer held, so it grows.
            slots.begin(1);
            device.submitAndWait(device.graphics(), commands -> {
                small.upload(commands); // nothing changed: nothing to do
                for (int i = 0; i < 3; i++) {
                    small.set(i, quad, 0, i, 0, -8, HALF, 0, 0, HALF, 1, 1, 1);
                }
                small.upload(commands);
                small.remove(0);
                small.remove(1);
                small.remove(2);
                small.upload(commands);
            });
            slots.end();
            slots.begin(0);
            renderWith(commands -> drawing.record(commands, ahead, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
            slots.end();
            assertThat(ahead.visibleMeshlets()).as("all removed").isZero();
        }
    }

    @Test
    void farFromTheOriginTheImageIsTheSame() {
        // The terrain and a quad near the origin, then the same 7,000 km away with the camera there: positions are kept
        // in two floats per axis and drawn relative to the camera, so the image does not change.
        scene.set(0, terrain, 0, -3, -8, 0, 0, 0, 1, 1, 1, 1);
        scene.set(1, quad, 1, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        scene.removeFrom(2);
        lookForward(1, MeshletRenderer.MODE_LIT);
        render();
        int[] near = image();
        double wx = 3_123_456.789;
        double wy = -45_678.9;
        double wz = -7_000_123.456;
        scene.set(0, terrain, 0, wx, wy - 3, wz - 8, 0, 0, 0, 1, 1, 1, 1);
        scene.set(1, quad, 0, wx + 1, wy, wz - 5, HALF, 0, 0, HALF, 1, 1, 1);
        camera.position(wx, wy, wz);
        lookForward(1, MeshletRenderer.MODE_LIT);
        render();
        int[] far = image();
        int differing = 0;
        for (int i = 0; i < near.length; i++) {
            differing += near[i] == far[i] ? 0 : 1;
        }
        assertThat(differing).as("pixels differing from the image near the origin").isLessThanOrEqualTo(SIZE / 8);
        // Floats alone, as positions used to be kept, can only put the quad on a grid of quarter and half units
        // there: drawn visibly off.
        scene.set(1, quad, 0, wx + 1.3, wy + 0.1, wz - 5, HALF, 0, 0, HALF, 1, 1, 1);
        render();
        int[] exact = image();
        scene.set(1, quad, 0, (float) (wx + 1.3), (float) (wy + 0.1), (float) (wz - 5), HALF, 0, 0, HALF,
            1, 1, 1);
        render();
        int[] rounded = image();
        int off = 0;
        for (int i = 0; i < exact.length; i++) {
            off += exact[i] == rounded[i] ? 0 : 1;
        }
        assertThat(off).as("pixels off with floats alone").isGreaterThan(SIZE);
        camera.position(0, 0, 0);
    }


    @Test
    void viewsLieInOneArrayWithTheFrameBeforesMatrixAndJitter() {
        RenderView second = renderer.createView(ViewOptions.of(16, 64));
        assertThat(second.address() - view.address()).isEqualTo((long) RenderView.BYTES * (second.index() - view.index()));
        assertThat(renderer.viewConstantsAddress()).isEqualTo(view.address() - (long) RenderView.BYTES * view.index());
        Camera moving = new Camera().position(10, 0, 0).set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        second.jitter(0.25f, -0.5f).output(SIZE, SIZE / 2).set(moving, 1, MeshletRenderer.MODE_WHITE);
        // The first time, the frame before is this one.
        float[] matrix = moving.viewProjection().clone();
        for (int i = 0; i < 16; i++) {
            assertThat(second.getFloat(ViewLayout.PREVIOUS_VIEW_PROJECTION + 4L * i)).isEqualTo(matrix[i]);
        }
        assertThat(second.getFloat(ViewLayout.JITTER)).isEqualTo(0.25f);
        assertThat(second.getFloat(ViewLayout.PREVIOUS_JITTER)).isZero();
        // Moved 3 units along x: a point fixed in the world, relative to the new camera, lands where it was before.
        moving.position(13, 0, 0);
        second.jitter(-0.25f, 0.5f).set(moving, 1, MeshletRenderer.MODE_WHITE);
        float[] previous = new float[16];
        for (int i = 0; i < 16; i++) {
            previous[i] = second.getFloat(ViewLayout.PREVIOUS_VIEW_PROJECTION + 4L * i);
        }
        float[] point = {-3, 0.5f, -4}; // relative to the camera at 13: the world point (10, 0.5, -4)
        float[] before = {0, 0.5f, -4}; // relative to the camera at 10
        for (int row = 0; row < 4; row++) {
            float now = previous[row] * point[0] + previous[4 + row] * point[1] + previous[8 + row] * point[2]
                + previous[12 + row];
            float then = matrix[row] * before[0] + matrix[4 + row] * before[1] + matrix[8 + row] * before[2]
                + matrix[12 + row];
            assertThat(now).isCloseTo(then, org.assertj.core.data.Offset.offset(1e-4f));
        }
        assertThat(second.getFloat(ViewLayout.PREVIOUS_JITTER)).isEqualTo(0.25f);
        assertThat(second.getFloat(ViewLayout.PREVIOUS_JITTER + 4)).isEqualTo(-0.5f);
        assertThat(second.getFloat(ViewLayout.JITTER)).isEqualTo(-0.25f);
        assertThatIllegalArgumentException().isThrownBy(() -> second.output(-1, 1));
    }

    @Test
    void camerasAtTheirOwnPositionsAreCulledTogether() {
        // Forty cameras, each 5 units in front of a quad of its own, the quads 1,000 units apart far from the origin:
        // views as many as there are, culled in one pass, each seeing only its quad.
        int views = 40;
        Camera[] cameras = new Camera[views];
        RenderView[] all = new RenderView[views];
        for (int v = 0; v < views; v++) {
            double x = 5e6 + 1000 * v;
            scene.set(v, quad, 0, x, 2e4, -3e6, HALF, 0, 0, HALF, 1, 1, 1);
            cameras[v] = new Camera().position(x, 2e4, -3e6 + 5).set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
            all[v] = v == 0 ? view : renderer.createView(ViewOptions.of(1024, 1 << 12));
            all[v].set(cameras[v], 1, MeshletRenderer.MODE_WHITE);
        }
        renderWith(commands -> {
            renderer.cullViews(commands, all, new boolean[views], views);
            renderer.drawView(commands, all[37], color.view(), depth.view(), SIZE, SIZE, 0, 0, 0);
        });
        for (int v = 0; v < views; v++) {
            assertThat(all[v].visibleMeshlets()).as("view %d", v).isEqualTo(1);
        }
        // View 37 drew its quad where a camera 5 units in front of it sees it: the middle, 44 pixels across.
        assertThat(pixel(SIZE / 2, SIZE / 2)).isEqualTo(WHITE);
        assertThat(covered()).isCloseTo(44 * 44, org.assertj.core.data.Percentage.withPercentage(10));
        assertThat(all[37].position(0)).isEqualTo(5e6 + 37_000);
        assertThat(all[37].index()).isEqualTo(37);
        assertThat(renderer.views()).hasSize(views);
        // Two views close: they leave the renderer, can no longer be drawn, and their indices go to the next views.
        long bytes = renderer.viewBytes();
        all[5].close();
        all[20].close();
        all[20].close(); // twice is fine
        assertThat(renderer.views()).hasSize(views - 2).doesNotContain(all[5], all[20]);
        assertThat(renderer.viewBytes()).isLessThan(bytes);
        RenderView closed = all[5];
        assertThatIllegalStateException().isThrownBy(() -> renderer.drawView(null, closed, 0, 0, 1, 1, 0, 0, 0));
        all[5] = renderer.createView(ViewOptions.of(1024, 1 << 12));
        all[20] = renderer.createView(ViewOptions.of(1024, 1 << 12));
        assertThat(all[5].index()).isEqualTo(5);
        assertThat(all[20].index()).isEqualTo(20);
        assertThat(renderer.createView(ViewOptions.of(16, 64)).index()).isEqualTo(views);
        all[5].set(cameras[5], 1, MeshletRenderer.MODE_WHITE);
        all[20].set(cameras[20], 1, MeshletRenderer.MODE_WHITE);
        renderWith(commands -> renderer.cullViews(commands, all, new boolean[views], views));
        for (int v = 0; v < views; v++) {
            assertThat(all[v].visibleMeshlets()).as("view %d, after views closed", v).isEqualTo(1);
        }
    }

    /**
     * A quad in the x-y plane facing +z, 2 units across, with normals, tangents and texture coordinates (4 repeats
     * across): its left half one submesh, its right half another.
     */
    static MeshData texturedQuad() {
        int n = 4;
        int count = (n + 1) * (n + 1);
        float[] positions = new float[3 * count];
        float[] normals = new float[3 * count];
        float[] tangents = new float[4 * count];
        float[] uvs = new float[2 * count];
        for (int y = 0; y <= n; y++) {
            for (int x = 0; x <= n; x++) {
                int v = y * (n + 1) + x;
                positions[3 * v] = 2f * x / n - 1;
                positions[3 * v + 1] = 2f * y / n - 1;
                normals[3 * v + 2] = 1;
                tangents[4 * v] = 1;
                tangents[4 * v + 3] = 1;
                uvs[2 * v] = 4f * x / n;
                uvs[2 * v + 1] = 4f * y / n;
            }
        }
        int[] left = new int[n * n * 3];
        int[] right = new int[n * n * 3];
        int l = 0;
        int r = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int a = y * (n + 1) + x;
                int[] cell = {a, a + 1, a + n + 2, a, a + n + 2, a + n + 1}; // counter-clockwise, seen from +z
                if (x < n / 2) {
                    System.arraycopy(cell, 0, left, l, 6);
                    l += 6;
                } else {
                    System.arraycopy(cell, 0, right, r, 6);
                    r += 6;
                }
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT, VertexAttribute.UV0),
            count).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.TANGENT, tangents).attribute(VertexAttribute.UV0, uvs)
            .submesh(java.util.Arrays.copyOf(left, l)).submesh(java.util.Arrays.copyOf(right, r)).buildFlat();
    }

    /** {@code size} x {@code size} texels of {@code rgba} each, in an arena that lives as long as the test. */
    private static java.lang.foreign.MemorySegment texels(int size, java.util.function.IntBinaryOperator rgba) {
        java.lang.foreign.MemorySegment data = java.lang.foreign.Arena.ofAuto().allocate(4L * size * size, 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                data.setAtIndex(ValueLayout.JAVA_INT, (long) y * size + x, rgba.applyAsInt(x, y));
            }
        }
        return data;
    }

    /** The average of channel {@code shift} (0 red, 8 green, 16 blue) over columns {@code from} to {@code to}. */
    private double channel(int shift, int from, int to) {
        double sum = 0;
        for (int y = SIZE / 2 - 16; y < SIZE / 2 + 16; y++) {
            for (int x = from; x < to; x++) {
                sum += (pixel(x, y) >> shift) & 0xff;
            }
        }
        return sum / (32.0 * (to - from));
    }

    @Test
    void materialsShadeEachSubmeshWithItsTextures() {
        int quadMesh = pool.add(texturedQuad());
        try (Textures textures = new Textures(device); Materials materials = new Materials(device, 8)) {
            // Red and blue squares of 2 x 2 texels; normals tilted up, towards the sun.
            int checker = textures.color(8, 8, texels(8, (x, y) -> ((x / 2 + y / 2) % 2 == 0) ? 0xff0000ff
                : 0xffff0000));
            int tilted = textures.data(4, 4, texels(4, (x, y) -> 0xffd9d980)); // (0, 0.7, 0.7)
            assertThat(textures.count()).isEqualTo(2);
            assertThat(textures.bytes()).isGreaterThan(4 * 64);
            int first = materials.add(new Material(MR.withBaseColor(new Color(1, 1, 1, 1)).withBaseColorTexture(checker)
                .withRoughness(1)));
            materials.add(new Material(GREEN));
            materials.add(new Material(GREEN));
            materials.add(new Material(GREEN.withNormalTexture(tilted).withNormalScale(1)));
            assertThat(materials.count()).isEqualTo(5);
            scene.setMaterials(materials);
            scene.set(0, quadMesh, first, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1);
            scene.removeFrom(1);
            lookForward(1, MeshletRenderer.MODE_LIT);
            render();
            // The left half shows the checker: red and blue texels; the right half is green.
            int[] colors = new int[2];
            for (int x = SIZE / 2 - 18; x < SIZE / 2 - 2; x++) {
                int p = pixel(x, SIZE / 2 + 3);
                colors[(p & 0xff) > ((p >> 16) & 0xff) ? 0 : 1]++;
            }
            assertThat(colors[0]).as("red texels").isGreaterThan(3);
            assertThat(colors[1]).as("blue texels").isGreaterThan(3);
            assertThat(channel(8, SIZE / 2 + 3, SIZE / 2 + 18))
                .isGreaterThan(3 * channel(0, SIZE / 2 + 3, SIZE / 2 + 18));
            // From the next material on: green both sides, the right one with the normal map facing the sun more.
            scene.set(0, quadMesh, first + 2, 0, 0, -5, 0, 0, 0, 1, 1, 1, 1);
            render();
            double flat = channel(8, SIZE / 2 - 18, SIZE / 2 - 3);
            double bent = channel(8, SIZE / 2 + 3, SIZE / 2 + 18);
            assertThat(bent).isGreaterThan(flat * 1.3);
            // Changed in place: the right half is plain again.
            materials.set(first + 3, new Material(GREEN));
            render();
            assertThat(channel(8, SIZE / 2 + 3, SIZE / 2 + 18))
                .isCloseTo(flat, org.assertj.core.data.Offset.offset(2.0));
            scene.setMaterials(null);
            assertThatIllegalArgumentException().isThrownBy(() -> materials.set(5, Material.DEFAULT));
            assertThatIllegalArgumentException().isThrownBy(() -> materials.set(-1, Material.DEFAULT));
            for (int i = materials.count(); i < 8; i++) {
                materials.add(new Material(MR.withMetallic(1).withEmissive(new Color(1, 0, 0, 1))
                    .withEmissiveTexture(checker).withOrmTexture(tilted).withOcclusion(1)));
            }
            assertThatIllegalStateException().isThrownBy(() -> materials.add(Material.DEFAULT));
            assertThatIllegalArgumentException().isThrownBy(() -> textures.color(4, 4, texels(2, (x, y) -> 0)));
            assertThatIllegalArgumentException().isThrownBy(() -> textures.data(0, 4, texels(2, (x, y) -> 0)));
            assertThatIllegalArgumentException().isThrownBy(() -> scene.set(0, quadMesh, -1, 0, 0, 0, 0,
                0, 0, 1, 1, 1, 1));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new Materials(device, 0));
    }

    @Test
    void closedTexturesTakeNoMore() {
        Textures textures = new Textures(device);
        assertThat(textures.color(1, 1, texels(1, (x, y) -> -1))).isNotNegative(); // one level only
        textures.close();
        textures.close();
        assertThatIllegalStateException().isThrownBy(() -> textures.color(1, 1, texels(1, (x, y) -> -1)));
    }

    @Test
    void devicesWithoutMeshShadersDrawIndexed() {
        assumeThat(device.meshShaders()).as("the device has mesh shaders").isFalse();
        assertThat(renderer.path()).isEqualTo(MeshletRenderer.Path.INDEXED);
        assertThatIllegalStateException().isThrownBy(() -> renderer.setPath(MeshletRenderer.Path.MESH_SHADERS));
    }

    /** The colors of every pixel, read back. */
    private int[] image() {
        int[] image = new int[SIZE * SIZE];
        for (int i = 0; i < image.length; i++) {
            image[i] = pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return image;
    }

    @Test
    void removedMeshesMakeRoomForOthers() {
        MeshData hill = grid(16, 0.2f, true, VertexFormat.of(VertexAttribute.NORMAL));
        MeshData square = grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        lookForward(1, MeshletRenderer.MODE_LIT);
        // The hill, in a fresh pool of its own.
        int[] fresh;
        try (GeometryPool own = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
             GpuScene ownScene = new GpuScene(device, own, 4);
             MeshletRenderer drawing = new MeshletRenderer(device, ownScene, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT)) {
            ownScene.set(0, own.add(hill), 0, -1, -3, 0.3f, 0, 0, 0.954f, 1, 1, 1);
            RenderView ownView = drawing.createView(ViewOptions.of(1024, 1 << 16)).set(camera, 1,
                MeshletRenderer.MODE_LIT);
            renderWith(commands -> drawing.record(commands, ownView, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0));
            fresh = image();
        }
        assertThat(java.util.Arrays.stream(fresh).filter(c -> (c & 0xffffff) != 0).count()).isPositive();
        // In the shared pool, where the terrain was: the same number, other ranges, the same image.
        int vertices = pool.vertexCount();
        pool.remove(terrain);
        assertThat(pool.contains(terrain)).isFalse();
        assertThat(pool.meshCount()).isEqualTo(1);
        assertThat(pool.vertexCount()).isEqualTo(vertices - 65 * 65);
        assertThatIllegalArgumentException().isThrownBy(() -> scene.set(0, terrain, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1));
        assertThatIllegalArgumentException().isThrownBy(() -> pool.remove(terrain));
        assertThatIllegalArgumentException().isThrownBy(() -> pool.remove(99));
        int squareId = pool.add(square); // the terrain's number, the lowest free one
        assertThat(squareId).isEqualTo(terrain);
        int hillId = pool.add(hill);
        assertThat(hillId).isEqualTo(2);
        assertThat(pool.meshSlots()).isEqualTo(3);
        scene.set(0, hillId, 0, -1, -3, 0.3f, 0, 0, 0.954f, 1, 1, 1);
        render();
        assertThat(image()).isEqualTo(fresh);
        // And the square in the terrain's place draws as the quad does.
        scene.set(0, squareId, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        view.set(camera, 1, MeshletRenderer.MODE_WHITE);
        render();
        assertThat(covered()).isCloseTo(44 * 44, org.assertj.core.data.Percentage.withPercentage(10));
    }

    @Test
    void removedMeshesWaitForTheFramesInFlight() {
        MeshData square = grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        com.truesim.engine.vulkan.FrameSlots slots = new com.truesim.engine.vulkan.FrameSlots(2);
        try (GeometryPool own = new GeometryPool(device, GeometryPool.Capacity.of(8, 1, 1), slots)) {
            int first = own.add(square);
            assertThat(own.fitsMeshShaders()).isTrue();
            slots.begin(0);
            own.remove(first);
            slots.end();
            // Frames 0 and the next may still draw it: no room yet.
            assertThatIllegalStateException().isThrownBy(() -> own.add(square));
            slots.begin(1);
            slots.end();
            assertThatIllegalStateException().isThrownBy(() -> own.add(square));
            slots.begin(0); // waited for frame 0: nothing in flight can draw it
            assertThat(own.add(square)).isEqualTo(first);
            slots.end();
            assertThat(own.meshCount()).isEqualTo(1);
        }
    }

    @Test
    void removedMaterialsAndTexturesAreReused() {
        com.truesim.engine.vulkan.FrameSlots slots = new com.truesim.engine.vulkan.FrameSlots(2);
        try (Materials materials = new Materials(device, 3, slots); Textures textures = new Textures(device, slots);
             Arena arena = Arena.ofConfined()) {
            int a = materials.add(new Material(MR.withRoughness(0.1f)));
            int b = materials.add(new Material(MR.withRoughness(0.2f)));
            assertThat(materials.count()).isEqualTo(3);
            assertThatIllegalStateException().isThrownBy(() -> materials.add(Material.DEFAULT));
            materials.remove(a);
            assertThat(materials.contains(a)).isFalse();
            assertThat(materials.count()).isEqualTo(2);
            assertThatIllegalArgumentException().isThrownBy(() -> materials.set(a, Material.DEFAULT));
            assertThatIllegalArgumentException().isThrownBy(() -> materials.remove(a));
            assertThatIllegalArgumentException().isThrownBy(() -> materials.remove(0)); // the default stays
            assertThatIllegalStateException().isThrownBy(() -> materials.add(Material.DEFAULT)); // still in flight
            MemorySegment texels = arena.allocate(4 * 4 * 4);
            int slot = textures.color(4, 4, texels);
            int inUse = device.bindless().slotsInUse();
            assertThat(textures.contains(slot)).isTrue();
            assertThat(textures.bytes()).isPositive();
            textures.remove(slot);
            assertThat(textures.contains(slot)).isFalse();
            assertThat(textures.count()).isZero();
            assertThat(textures.bytes()).isZero();
            assertThat(device.bindless().slotsInUse()).isEqualTo(inUse); // still sampled by frames in flight
            assertThatIllegalArgumentException().isThrownBy(() -> textures.remove(slot));
            for (int frame = 0; frame < 2; frame++) {
                slots.begin(frame);
                slots.end();
            }
            assertThat(materials.add(Material.DEFAULT)).isEqualTo(a);
            assertThat(materials.contains(b)).isTrue();
            int again = textures.data(4, 4, texels); // frees the removed one first, then takes a slot
            assertThat(device.bindless().slotsInUse()).isEqualTo(inUse);
            assertThat(textures.count()).isEqualTo(1);
            textures.remove(again);
        }
    }

    @Test
    void misuseIsChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> scene.set(256, quad, 0, 0, 0, 0, 0, 0, 1, 1,
            1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.set(0, 5, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GpuScene(device, pool, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> GeometryPool.Capacity.of(0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> GeometryPool.Capacity.of(1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GeometryPool.Capacity(1, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> GeometryPool.Capacity.of(1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.set(-1, quad, 0, 0, 0, 0, 0, 0, 1, 1,
            1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.set(0, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer(device, scene,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, com.truesim.engine.vulkan.FrameSlots.single(), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer(device, scene,
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT, com.truesim.engine.vulkan.FrameSlots.single(), 65_536));
        // Each limit of the pool: meshes, meshlets, triangles, meshlet vertices, attributes.
        MeshData small = grid(2, 1, false, VertexFormat.of(VertexAttribute.NORMAL));
        for (GeometryPool.Capacity limit : new GeometryPool.Capacity[] {
            new GeometryPool.Capacity(1000, 100, 1000, 1), new GeometryPool.Capacity(1000, 1, 1000, 4),
            new GeometryPool.Capacity(1000, 100, 9, 4), new GeometryPool.Capacity(13, 100, 1000, 4)}) {
            try (GeometryPool limited = new GeometryPool(device, limit)) {
                limited.add(small);
                assertThatIllegalStateException().as("%s", limit).isThrownBy(() -> limited.add(small));
            }
        }
        assertThat(pool.meshCount()).isEqualTo(2);
        assertThat(pool.capacity().meshes()).isEqualTo(8);
        assertThat(pool.vertexCount()).isEqualTo(4 + 65 * 65);
        assertThat(pool.meshletCount()).isPositive();
        try (GeometryPool tiny = new GeometryPool(device, GeometryPool.Capacity.of(4, 1, 1))) {
            assertThatIllegalStateException().isThrownBy(() -> tiny.add(grid(8, 1, false,
                VertexFormat.POSITIONS_ONLY)));
            tiny.close();
            assertThatIllegalStateException().isThrownBy(() -> tiny.add(grid(1, 1, false,
                VertexFormat.POSITIONS_ONLY)));
        }
    }
}
