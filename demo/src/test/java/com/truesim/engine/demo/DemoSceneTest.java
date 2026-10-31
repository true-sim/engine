package com.truesim.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The demo's scene drawn offscreen as the demo draws it, validated: its camera set only once frames run, a rebuild
 * after the first frames, frames in flight. The terrain fills a view looking down on it.
 */
class DemoSceneTest {

    private static final int SIZE = 1024;

    @Test
    void theSceneShowsFromTheStart() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            return;
        }
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (instance; VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2);
             DemoScene world = new DemoScene(device, graph.frameSlots(), org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
             GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            ExternalImage output = new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
            Camera camera = new Camera(); // set when the frames run, as the demo's
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView view = world.geometry().createView(port, camera, ViewOptions.of(1 << 12, 1 << 20)
                .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
            port.add(new ClusteredForwardShading(world.geometry(), view, org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
                .clearColor(0, 0, 1)).add(new ClusteredLights(world.lights(), view)).add(new Probe()).output(Keys.SCENE_COLOR, output);
            int sky = 0;
            for (int frame = 0; frame < 4; frame++) {
                world.update(frame / 60.0); // as the demo: every frame, before it begins
                camera.position(0, 20, 0).set(0, -1, 0, 0, 0, -1, Math.toRadians(70), SIZE, SIZE, 0.1);
                graph.beginFrame();
                graph.submit(0, 0);
                if (frame == 1) {
                    graph.invalidate(); // rebuilt, as a window's first resize does
                }
            }
            device.waitIdle();
            {
                device.submitAndWait(device.graphics(), commands -> {
                    try (MemoryStack stack = stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                        region.imageExtent().set(SIZE, SIZE, 1);
                        vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            pixels.handle(), region);
                    }
                });
            }
            for (int i = 0; i < SIZE * SIZE; i++) {
                sky += pixels.memory().getAtIndex(ValueLayout.JAVA_INT, i) == 0xffff0000 ? 1 : 0;
            }
            System.out.println("visible meshlets " + view.view().visibleMeshlets() + ", sky pixels " + sky);
            device.waitIdle();
            assertThat(instance.validationErrors()).isZero();
            assertThat(sky).as("the terrain below covers the view").isLessThan(SIZE * SIZE / 10);
        }
    }
}
