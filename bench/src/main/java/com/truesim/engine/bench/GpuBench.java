package com.truesim.engine.bench;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.GpuUiRenderer;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;

/**
 * A headless GPU with one view of the benchmark's size whose {@link Keys#UI} a {@link GpuUiRenderer} draws, without
 * validation, timed by the graph's timestamps: each {@link #frame} records a frame, waits for it and returns the GPU
 * time of the UI pass plus the frame start (uploads), in milliseconds.
 */
final class GpuBench implements AutoCloseable {

    final VulkanInstance instance;
    final VulkanDevice device;
    final FrameGraph graph;
    final ViewPort view;
    final GpuUiRenderer renderer;
    private final GpuImage target;
    /** The frame start's part (uploads) of the last {@link #frame}, ms. */
    double lastStart;

    private GpuBench(VulkanInstance instance, int width, int height) {
        this.instance = instance;
        device = VulkanDevice.createHeadless(instance.bestGpu());
        graph = new FrameGraph(device, 2);
        view = graph.addView("main").size(width, height);
        target = new GpuImage(device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        ExternalImage out = new ExternalImage("ui out", width, height, Format.R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        view.output(Keys.UI, out);
        renderer = new GpuUiRenderer(graph);
        view.add(renderer);
        graph.timings(true);
    }

    /** A rig of {@code width x height}, or null without a Vulkan 1.3 GPU. */
    static GpuBench open(int width, int height) {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("engine-bench").validation(false).surfaces(false)
                .build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            return null;
        }
        if (instance.gpus().isEmpty()) {
            instance.close();
            return null;
        }
        return new GpuBench(instance, width, height);
    }

    /** The GPU's name and driver. */
    String gpuName() {
        return device.gpu().name();
    }

    /** Records and waits for a frame: the UI pass's and the frame start's GPU time, ms (NaN before timings come). */
    double frame() {
        device.submitAndWait(device.graphics(), graph::record);
        GpuTimings timings = graph.timings();
        if (timings == null) {
            return Double.NaN;
        }
        double start = timings.frameStartMillis();
        lastStart = Double.isNaN(start) ? 0 : start;
        double sum = lastStart; // NaN: nothing recorded at the frame start
        boolean found = false;
        for (int t = 0; t < timings.count(); t++) {
            if (timings.pass(t).equals("ui") && !Double.isNaN(timings.millis(t))) {
                sum += timings.millis(t);
                found = true;
            }
        }
        return found ? sum : Double.NaN;
    }

    @Override
    public void close() {
        device.waitIdle();
        renderer.close();
        graph.close();
        target.close();
        device.close();
        instance.close();
    }
}
