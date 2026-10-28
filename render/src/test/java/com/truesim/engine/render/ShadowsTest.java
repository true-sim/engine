package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.render.shadows.RayTracedShadows;
import com.truesim.engine.render.shadows.ShadowMapShadows;
import com.truesim.engine.render.shadows.ShadowedScene;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Sun shadows through the frame graph, validated (synchronization too): ray-traced where the device can, and by a
 * shadow map, swapped behind one key. A 2 x 2 quad floats 2 units over a 20 x 20 floor; its shadow falls where the sun
 * direction says, and the two techniques agree on almost every pixel.
 */
class ShadowsTest {

    private static final int SIZE = 128;

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private MeshletRenderer renderer;
    private RayTracing rayTracing;
    private FrameGraph graph;
    private GpuImage maskTarget;
    private GpuImage colorTarget;
    private GpuBuffer pixels;
    private final Camera camera = new Camera();
    private final Camera light = new Camera();
    private MeshData quadMesh;
    private int quad;

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
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4));
        quadMesh = MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY);
        quad = pool.add(quadMesh);
        renderer = new MeshletRenderer(device, pool, new MeshletRenderer.Limits(16, 256, 64, 1 << 12, 2),
            VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT);
        // The camera at the origin looks down at the floor (y = -5, around z = -5); the quad floats at y = -3.
        renderer.setInstance(0, quad, 0, -5, -5, 0, 0, 0, 1, 10, 1, 10);
        renderer.setInstance(1, quad, 0, -3, -5, 0, 0, 0, 1, 1, 1, 1);
        renderer.setInstanceCount(2);
        camera.set(0, -1, -1, 0, 1, 0, Math.PI / 3, SIZE, SIZE, 0.1);
        renderer.setView(camera, 1, MeshletRenderer.MODE_WHITE);
        // The sun's view: 20 units back along the sun from the floor's middle, 16 x 16 units, 40 deep.
        light.setOrthographic(20 * Keys.SUN_X, -5 + 20 * Keys.SUN_Y, -5 + 20 * Keys.SUN_Z, -Keys.SUN_X, -Keys.SUN_Y,
            -Keys.SUN_Z, 1, 0, 0, 8, 8, 40, 1024, 1024);
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        maskTarget = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        colorTarget = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            if (rayTracing != null) {
                rayTracing.close();
            }
            pixels.close();
            colorTarget.close();
            maskTarget.close();
            renderer.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Records a frame and reads back {@code target}: one byte per texel for the mask, four for color. */
    private byte[] frame(GpuImage target, int bytesPerTexel) {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        byte[] texels = new byte[SIZE * SIZE * bytesPerTexel];
        pixels.memory().asSlice(0, texels.length).asByteBuffer().get(texels);
        return texels;
    }

    private ExternalImage external(GpuImage image, int format) {
        return new ExternalImage("target", SIZE, SIZE, format, VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .set(image.handle(), image.view());
    }

    /** The pixel camera-relative point (x, y, z) projects to: x + y * SIZE. */
    private int pixel(double x, double y, double z) {
        float[] m = camera.viewProjection();
        double cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        double cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        double w = m[3] * x + m[7] * y + m[11] * z + m[15];
        int px = (int) ((cx / w * 0.5 + 0.5) * SIZE);
        int py = (int) ((cy / w * 0.5 + 0.5) * SIZE);
        return px + py * SIZE;
    }

    /** Checks a mask: the floor under the quad's shadow dark, the floor elsewhere, the quad and the sky lit. */
    private void expectShadow(byte[] mask, String technique) {
        // The shadow of the quad's middle (0, -3, -5) on the floor, 2 below, along the sun.
        double run = 2 / Keys.SUN_Y;
        double sx = -Keys.SUN_X * run;
        double sz = -5 - Keys.SUN_Z * run;
        assertThat(mask[pixel(sx, -5, sz)] & 0xff).as("%s: the floor in shadow", technique).isLessThan(20);
        assertThat(mask[pixel(sx + 0.6, -5, sz - 0.6)] & 0xff).as("%s: still in shadow", technique).isLessThan(20);
        assertThat(mask[pixel(3, -5, -2)] & 0xff).as("%s: the floor in the sun", technique).isGreaterThan(235);
        assertThat(mask[pixel(0.5, -3, -4.5)] & 0xff).as("%s: the quad itself", technique).isGreaterThan(235);
        assertThat(mask[0] & 0xff).as("%s: the sky", technique).isEqualTo(255);
    }

    @Test
    void bothTechniquesCastTheSameShadowBehindOneKey() {
        graph.add(new MeshletScene(renderer, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT))
            .add(new ShadowMapShadows(renderer, camera, light, 1024, 1, 1.5e-3f))
            .output(Keys.SHADOW_MASK, external(maskTarget, VK_FORMAT_R8_UNORM));
        byte[] mapped = frame(maskTarget, 1);
        expectShadow(mapped, "shadow map");
        assertThat(graph.passes()).contains("shadow map", "shadow-mapped shadows")
            .containsOnlyOnce("meshlet culling"); // the camera and the sun culled together
        assertThat(renderer.visibleMeshlets(1)).isEqualTo(2); // the sun's view drew both quads

        if (device.rayTracing()) {
            rayTracing = new RayTracing(device, pool, 16);
            rayTracing.add(quad, quadMesh, quadMesh.levels().size() - 1);
            assertThat(rayTracing.bottomLevelBytes()).isPositive();
            assertThatIllegalStateException().isThrownBy(() -> rayTracing.add(quad, quadMesh, 0));
            graph.replace(new RayTracedShadows(rayTracing, renderer, camera));
            byte[] traced = frame(maskTarget, 1);
            expectShadow(traced, "ray tracing");
            assertThat(graph.passes()).contains("ray-traced shadows").doesNotContain("shadow map");
            int differ = 0;
            for (int i = 0; i < traced.length; i++) {
                differ += Math.abs((traced[i] & 0xff) - (mapped[i] & 0xff)) > 64 ? 1 : 0;
            }
            System.out.printf("Shadows: %d of %d pixels differ between ray tracing and the shadow map; bottom levels "
                + "%d bytes (%s vertices)%n", differ, traced.length, rayTracing.bottomLevelBytes(),
                rayTracing.quantizedVertices() ? "quantized" : "decoded");
            assertThat(differ).isLessThan(traced.length / 50); // edges only
        }

        // The composite: shadowed pixels keep the ambient share.
        graph.add(new ShadowedScene(0.3f)).output(Keys.SHADOWED_COLOR, external(colorTarget, VK_FORMAT_R8G8B8A8_UNORM))
            .removeOutput(Keys.SHADOW_MASK);
        byte[] color = frame(colorTarget, 4);
        double run = 2 / Keys.SUN_Y;
        int shadowed = color[4 * pixel(-Keys.SUN_X * run, -5, -5 - Keys.SUN_Z * run)] & 0xff;
        int lit = color[4 * pixel(3, -5, -2)] & 0xff;
        assertThat(lit).isEqualTo(255); // white
        assertThat(shadowed).isBetween(70, 85); // 0.3 of white
    }

    @Test
    void rayTracingNeedsTheDevice() {
        if (device.rayTracing()) {
            assertThat(device.bindless().slotsInUse()).isZero();
            try (RayTracing tracing = new RayTracing(device, pool, 4)) {
                assertThat(device.bindless().slotsInUse()).isEqualTo(1); // its top level
                assertThat(tracing.slot()).isZero();
            }
            assertThat(device.bindless().slotsInUse()).isZero();
        } else {
            assertThatIllegalStateException().isThrownBy(() -> new RayTracing(device, pool, 4));
            assertThatIllegalStateException().isThrownBy(() -> device.bindless().addAccelerationStructure(1));
        }
    }
}
