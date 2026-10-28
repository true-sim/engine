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
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/** The GPU-driven meshlet path, on the GPU: what is drawn, what is culled, and detail that keeps the surface whole. */
class MeshletRendererTest {

    private static final int SIZE = 128;
    private static final int WHITE = 0xffffffff;
    private static final float HALF = (float) Math.sqrt(0.5);

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private MeshletRenderer renderer;
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
        renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(256, 8192, 1024, 1 << 20),
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
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
    private static MeshData grid(int n, float size, boolean bumpy, VertexFormat format) {
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
        device.submitAndWait(device.graphics(), commands -> {
            Images.transition(commands, color.handle(), VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            Images.transition(commands, depth.handle(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            renderer.record(commands, color.view(), depth.view(), SIZE, SIZE, 0, 0, 0);
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
        return renderer.visibleMeshlets();
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
        renderer.setView(camera.set(0, 0, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), errorPixels, mode);
    }

    @Test
    void aQuadFacingTheCameraCoversWhereItShould() {
        // The quad faces +y; rotated 90 degrees about x it faces +z, the camera, 5 units ahead.
        renderer.setInstance(0, quad, 0, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);
        renderer.setInstanceCount(1);
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
        renderer.setInstance(0, quad, 0, 0, 5, HALF, 0, 0, HALF, 1, 1, 1);       // behind the camera
        renderer.setInstance(1, quad, 40, 0, -5, HALF, 0, 0, HALF, 1, 1, 1);     // far to the right
        renderer.setInstance(2, quad, 0, 0, -5, -HALF, 0, 0, HALF, 1, 1, 1);     // facing away
        renderer.setInstanceCount(3);
        lookForward(1, MeshletRenderer.MODE_WHITE);
        assertThat(render()).isZero();
        assertThat(covered()).isZero();
        renderer.setInstanceCount(0);
        assertThat(render()).isZero();
    }

    @Test
    void detailFollowsTheAllowedErrorWithoutHoles() {
        // The bumpy terrain, 64 x 64 units, seen from 40 units above and in front, looking down at 45 degrees.
        renderer.setInstance(0, terrain, 0, -40, -40, 0, 0, 0, 1, 1, 1, 1);
        renderer.setInstanceCount(1);
        int previous = Integer.MAX_VALUE;
        int full = -1;
        int fullCoverage = -1;
        for (float error : new float[] {0, 0.5f, 2, 8, 32, 1e6f}) {
            renderer.setView(camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), error,
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
        renderer.setView(camera, 1, MeshletRenderer.MODE_LIT);
        assertThat(render()).isPositive();
        renderer.setView(camera, 1, MeshletRenderer.MODE_MESHLETS);
        assertThat(render()).isPositive();
    }

    @Test
    void manyInstancesDrawInOnePass() {
        int n = 0;
        for (int x = -7; x <= 7; x++) {
            for (int y = -7; y <= 7; y++) {
                renderer.setInstance(n++, quad, x * 3, y * 3, -60, HALF, 0, 0, HALF, 1, 1, 1);
            }
        }
        renderer.setInstanceCount(n);
        lookForward(1, MeshletRenderer.MODE_WHITE);
        assertThat(render()).isEqualTo(n); // all 225 in view at 60 units
        assertThat(renderer.limits().draws()).isEqualTo(8192);
    }

    @Test
    void bothPathsDrawTheSameImage() {
        assumeThat(device.meshShaders()).as("no mesh shaders").isTrue();
        assertThat(renderer.path()).isEqualTo(MeshletRenderer.Path.MESH_SHADERS); // chosen where available
        renderer.setInstance(0, terrain, 0, -40, -40, 0, 0, 0, 1, 1, 1, 1);
        int n = 1;
        for (int i = 0; i < 20; i++) {
            renderer.setInstance(n++, quad, (i % 5 - 2) * 4, (i / 5 - 2) * 3, -15 - i, HALF, 0, 0, HALF, 1, 1, 1);
        }
        renderer.setInstanceCount(n);
        renderer.setView(camera.set(0, -0.3, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1), 1,
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
    void devicesWithoutMeshShadersDrawIndexed() {
        assumeThat(device.meshShaders()).as("the device has mesh shaders").isFalse();
        assertThat(renderer.path()).isEqualTo(MeshletRenderer.Path.INDEXED);
        assertThatIllegalStateException().isThrownBy(() -> renderer.setPath(MeshletRenderer.Path.MESH_SHADERS));
    }

    @Test
    void misuseIsChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.setInstance(256, quad, 0, 0, 0, 0, 0, 0, 1, 1,
            1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.setInstance(0, 5, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.setInstanceCount(257));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer.Limits(0, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> GeometryPool.Capacity.of(0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> GeometryPool.Capacity.of(1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GeometryPool.Capacity(1, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> GeometryPool.Capacity.of(1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.setInstance(-1, quad, 0, 0, 0, 0, 0, 0, 1, 1,
            1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.setInstance(0, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1));
        assertThatIllegalArgumentException().isThrownBy(() -> renderer.setInstanceCount(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer.Limits(1, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer.Limits(1, (1 << 22) + 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer.Limits(1, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshletRenderer.Limits(1, 1, 65_536, 1));
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
