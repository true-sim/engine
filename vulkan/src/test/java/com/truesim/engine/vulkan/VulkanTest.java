package com.truesim.engine.vulkan;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_MAKE_API_VERSION;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;
import static org.lwjgl.vulkan.VK13.vkCmdBeginRendering;
import static org.lwjgl.vulkan.VK13.vkCmdEndRendering;

import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.PlatformListener;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

class VulkanTest {

    /** A headless instance with validation, or the test is skipped where there is no Vulkan 1.3 GPU at all. */
    private static VulkanInstance headless() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        return instance;
    }

    /** Records clearing {@code view} of {@code image} to a color, with dynamic rendering. */
    private static void clear(VkCommandBuffer commands, long image, long view, int width, int height, float r, float g,
        float b, int finalLayout, long finalStage, long finalAccess) {
        Images.transition(commands, image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_ACCESS_2_NONE,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(view)
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.clearValue().color().float32(0, r).float32(1, g).float32(2, b).float32(3, 1);
            VkRenderingInfo info = VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .layerCount(1)
                .pColorAttachments(color);
            info.renderArea().extent().set(width, height);
            vkCmdBeginRendering(commands, info);
            vkCmdEndRendering(commands);
        }
        Images.transition(commands, image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            finalLayout, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            finalStage, finalAccess);
    }

    // ---------------------------------------------------------------- instance, GPUs, devices

    @Test
    void instanceListsGpus() {
        try (VulkanInstance instance = headless()) {
            assertThat(instance.handle()).isNotNull();
            List<Gpu> gpus = instance.gpus();
            for (Gpu gpu : gpus) {
                assertThat(gpu.name()).isNotBlank();
                assertThat(gpu.instance()).isSameAs(instance);
                assertThat(Integer.compareUnsigned(gpu.apiVersion(), VulkanInstance.MIN_API_VERSION)).isNotNegative();
                assertThat(gpu.queueFamilies()).anyMatch(QueueFamily::graphics);
                assertThat(gpu.extensions()).isNotEmpty();
                assertThat(gpu.toString()).contains(gpu.name(), gpu.type().toString(), "MiB");
                assertThat(gpu.vendorId()).isNotZero();
                assertThat(gpu.deviceId()).isNotNegative();
                assertThat(gpu.driverVersion()).isNotZero();
                assertThat(gpu.supports("VK_no_such_extension")).isFalse();
            }
            Gpu best = instance.bestGpu();
            for (Gpu gpu : gpus) {
                assertThat(best.type().compareTo(gpu.type())).isNotPositive();
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }

    @Test
    void instancesWorkWithoutValidation() {
        VulkanInstance plain;
        try {
            plain = VulkanInstance.builder().validation(false).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (VulkanInstance instance = plain) {
            assertThat(instance.validationEnabled()).isFalse();
            assertThat(instance.gpus()).isNotEmpty();
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                assertThat(device.canPresent()).isFalse();
            }
        }
    }

    @Test
    void devicesUseVulkan14WhereAvailable() {
        try (VulkanInstance instance = headless()) {
            assertThat(Integer.compareUnsigned(instance.apiVersion(), VulkanInstance.MIN_API_VERSION)).isNotNegative();
            for (Gpu gpu : instance.gpus()) {
                try (VulkanDevice device = VulkanDevice.createHeadless(gpu)) {
                    assertThat(device.apiVersion())
                        .isEqualTo(Vulkan.min(Vulkan.withoutPatch(gpu.apiVersion()), instance.apiVersion()));
                    if (device.apiVersion() == VulkanInstance.MAX_API_VERSION) {
                        // Mandatory in 1.4, so every 1.4 device has them enabled.
                        assertThat(device.features14().pushDescriptor()).isTrue();
                        assertThat(device.features14().maintenance5()).isTrue();
                        assertThat(device.features14().dynamicRenderingLocalRead()).isTrue();
                    } else {
                        assertThat(device.features14()).isEqualTo(Vulkan14Features.NONE);
                    }
                }
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }

    @Test
    void instancesCanBeLimitedToVulkan13() {
        try (VulkanInstance instance = VulkanInstance.builder().validation(true).surfaces(false)
            .maxApiVersion(VK_API_VERSION_1_3).build()) {
            assumeThat(instance.gpus()).isNotEmpty();
            assertThat(instance.apiVersion()).isEqualTo(VK_API_VERSION_1_3);
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                assertThat(device.apiVersion()).isEqualTo(VK_API_VERSION_1_3);
                assertThat(device.features14()).isEqualTo(Vulkan14Features.NONE);
            }
            assertThat(instance.validationErrors()).isZero();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> VulkanInstance.builder().maxApiVersion(VK_API_VERSION_1_2))
            .withMessage("Vulkan 1.2.0 is not between 1.3.0 and 1.4.0");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> VulkanInstance.builder().maxApiVersion(VK_MAKE_API_VERSION(0, 1, 5, 0)));
    }

    @Test
    void offscreenWindowsGetHeadlessSurfaces() {
        String headless = "VK_EXT_headless_surface";
        List<String> extensions = new java.util.ArrayList<>(List.of("VK_KHR_surface"));
        VulkanInstance.addHeadlessSurface("windows", java.util.Set.of(headless), extensions);
        assertThat(extensions).containsExactly("VK_KHR_surface");
        VulkanInstance.addHeadlessSurface("offscreen", java.util.Set.of(), extensions);
        assertThat(extensions).containsExactly("VK_KHR_surface");
        VulkanInstance.addHeadlessSurface("offscreen", java.util.Set.of(headless), extensions);
        VulkanInstance.addHeadlessSurface("offscreen", java.util.Set.of(headless), extensions);
        assertThat(extensions).containsExactly("VK_KHR_surface", headless);
    }

    @Test
    void hardwareDecisionsWorkForAnyHardware() {
        // GPU types and queue flags as drivers report them.
        assertThat(GpuType.of(VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)).isEqualTo(GpuType.DISCRETE);
        assertThat(GpuType.of(VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)).isEqualTo(GpuType.INTEGRATED);
        assertThat(GpuType.of(VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU)).isEqualTo(GpuType.VIRTUAL);
        assertThat(GpuType.of(VK10.VK_PHYSICAL_DEVICE_TYPE_CPU)).isEqualTo(GpuType.CPU);
        assertThat(GpuType.of(VK10.VK_PHYSICAL_DEVICE_TYPE_OTHER)).isEqualTo(GpuType.OTHER);
        assertThat(QueueFamily.of(2, 4, VK10.VK_QUEUE_GRAPHICS_BIT | VK10.VK_QUEUE_COMPUTE_BIT))
            .isEqualTo(new QueueFamily(2, 4, true, true, false));
        assertThat(QueueFamily.of(3, 1, VK10.VK_QUEUE_TRANSFER_BIT)).isEqualTo(new QueueFamily(3, 1, false, false, true));

        // Queue families: one family for everything, as on lavapipe ...
        QueueFamily all = new QueueFamily(0, 1, true, true, true);
        assertThat(VulkanDevice.chooseQueueFamilies("cpu", List.of(all))).containsExactly(all, all, all);
        // ... dedicated compute and transfer families, as on discrete GPUs ...
        QueueFamily graphicsOnly = new QueueFamily(0, 1, true, false, true); // cannot compute: not the main family
        QueueFamily compute = new QueueFamily(2, 4, false, true, true);
        QueueFamily transfer = new QueueFamily(3, 2, false, false, true);
        assertThat(VulkanDevice.chooseQueueFamilies("gpu", List.of(graphicsOnly, all, compute, transfer)))
            .containsExactly(all, compute, transfer);
        // ... dedicated compute without a transfer family ...
        assertThat(VulkanDevice.chooseQueueFamilies("gpu", List.of(all, compute))).containsExactly(all, compute, compute);
        // ... and none that can render.
        assertThatThrownBy(() -> VulkanDevice.chooseQueueFamilies("compute card", List.of(compute, transfer)))
            .isInstanceOf(VulkanException.class).hasMessage("compute card has no graphics queue");

        // Surface formats: the first sRGB one, else the first.
        try (MemoryStack stack = stackPush()) {
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(4, stack);
            int[][] offered = {
                {VK10.VK_FORMAT_R8G8B8A8_UNORM, KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
                {VK10.VK_FORMAT_B8G8R8A8_SRGB, 1000104002}, // sRGB format, other color space
                {VK10.VK_FORMAT_R8G8B8A8_SRGB, KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
                {VK10.VK_FORMAT_B8G8R8A8_SRGB, KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR}};
            for (int i = 0; i < offered.length; i++) {
                long address = formats.address(i);
                MemoryUtil.memPutInt(address + VkSurfaceFormatKHR.FORMAT, offered[i][0]);
                MemoryUtil.memPutInt(address + VkSurfaceFormatKHR.COLORSPACE, offered[i][1]);
            }
            assertThat(Swapchain.chooseFormat(formats)).isEqualTo(2);
            assertThat(Swapchain.chooseFormat(formats.position(3).slice())).isZero();
            assertThat(Swapchain.chooseFormat(VkSurfaceFormatKHR.calloc(1, stack))).isZero();
        }

        // Validation messages by severity.
        try (VulkanInstance instance = headless()) {
            int errors = instance.validationErrors();
            int warnings = instance.validationWarnings();
            instance.message(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT, "info");
            instance.message(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT, "warning");
            instance.message(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT, "error");
            assertThat(instance.validationWarnings()).isEqualTo(warnings + 1);
            assertThat(instance.validationErrors()).isEqualTo(errors + 1);
        }
    }

    @Test
    void surfacesNeedARunningPlatform() {
        assertThatThrownBy(() -> VulkanInstance.builder().build()).isInstanceOf(VulkanException.class)
            .hasMessageContaining("Platform");
    }

    @Test
    void everyGpuRendersOffscreenAtTheSameTime() {
        try (VulkanInstance instance = headless()) {
            List<VulkanDevice> devices = new ArrayList<>();
            for (Gpu gpu : instance.gpus()) {
                devices.add(VulkanDevice.createHeadless(gpu)); // one device per GPU, all open together
            }
            float[][] colors = {{0.25f, 0.5f, 0.75f}, {1, 0, 0.5f}, {0, 1, 0}};
            for (int d = 0; d < devices.size(); d++) {
                VulkanDevice device = devices.get(d);
                assertThat(device.canPresent()).isFalse();
                assertThat(device.compute().family()).isNotNegative();
                assertThat(device.transfer().family()).isNotNegative();
                float[] color = colors[d % colors.length];
                try (GpuImage image = new GpuImage(device, 64, 32, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
                     GpuBuffer readback = GpuBuffer.hostVisible(device, 64 * 32 * 4L, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
                    assertThat(image.width()).isEqualTo(64);
                    assertThat(image.height()).isEqualTo(32);
                    assertThat(image.format()).isEqualTo(VK_FORMAT_R8G8B8A8_UNORM);
                    device.submitAndWait(device.graphics(), commands -> {
                        clear(commands, image.handle(), image.view(), 64, 32, color[0], color[1], color[2],
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT);
                        try (MemoryStack stack = stackPush()) {
                            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                            region.imageExtent().set(64, 32, 1);
                            vkCmdCopyImageToBuffer(commands, image.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                readback.handle(), region);
                        }
                    });
                    assertThat(readback.isHostVisible()).isTrue();
                    assertThat(readback.size()).isEqualTo(64 * 32 * 4L);
                    for (int pixel : new int[] {0, 777, 64 * 32 - 1}) {
                        for (int channel = 0; channel < 3; channel++) {
                            int value = readback.memory().get(ValueLayout.JAVA_BYTE, pixel * 4L + channel) & 0xff;
                            assertThat(value).isCloseTo(Math.round(color[channel] * 255), org.assertj.core.api.Assertions.within(1));
                        }
                    }
                }
            }
            for (VulkanDevice device : devices) {
                device.close();
                device.close(); // twice is fine
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }

    @Test
    void resourcesValidate() {
        try (VulkanInstance instance = headless(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
            try (GpuBuffer local = GpuBuffer.deviceLocal(device, 256, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)) {
                assertThat(local.isHostVisible()).isFalse();
                assertThat(local.handle()).isNotZero();
                assertThatIllegalStateException().isThrownBy(local::memory);
                local.close(); // twice is fine
            }
            assertThatIllegalArgumentException().isThrownBy(() -> GpuBuffer.hostVisible(device, 0, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new GpuImage(device, 0, 4, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT));
            GpuImage image = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
            image.close();
            image.close(); // twice is fine
            assertThatIllegalArgumentException().isThrownBy(() -> new Frames(device, device.graphics(), 0, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> new Frames(device, device.graphics(), 1, 0));
            assertThatIllegalStateException().isThrownBy(() -> instance.createSurface(null))
                .withMessage("This instance was built without surfaces");
            // A failing submission reports its VkResult.
            assertThatThrownBy(() -> Vulkan.check(-4, "Lost it")).isInstanceOf(VulkanException.class)
                .hasMessage("Lost it: VK_ERROR_DEVICE_LOST").extracting(e -> ((VulkanException) e).result()).isEqualTo(-4);
            assertThat(new VulkanException("plain").result()).isZero();
            assertThat(Vulkan.version(VulkanInstance.MIN_API_VERSION)).isEqualTo("1.3.0");
            for (int result : new int[] {0, 1, 2, 1000001003, -1, -2, -3, -4, -6, -7, -8, -9, 1000000000 * -1, -1000001004, 12345}) {
                assertThat(Vulkan.resultName(result)).isNotBlank();
            }
            assertThat(VK_STRUCTURE_TYPE_SUBMIT_INFO).isNotNegative();
        }
    }

    // ---------------------------------------------------------------- windows

    /** Clears every window's next image to its color and presents it, on two recording workers. */
    private static void render(VulkanDevice device, List<Swapchain> swapchains, List<Frames> frames, float shade) {
        for (int i = 0; i < swapchains.size(); i++) {
            Swapchain swapchain = swapchains.get(i);
            Frames.Frame frame = frames.get(i).next();
            int image = swapchain.acquire(frame.imageAvailable());
            assertThat(image).isNotNegative();
            VkCommandBuffer clear = frame.begin(0);
            clear(clear, swapchain.image(image), swapchain.view(image), swapchain.width(), swapchain.height(),
                shade, 0.2f * i, 0.5f, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            frame.end(0);
            VkCommandBuffer present = frame.begin(1); // a second worker's commands run after the first's
            Images.transition(present, swapchain.image(image), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT, VK_ACCESS_2_NONE);
            frame.end(1);
            frame.submit(device.graphics(), swapchain.presentReady(image), true);
            swapchain.present(device.graphics(), image);
        }
    }

    @Test
    void windowsRenderAndResizeAtRuntime() {
        try (Platform platform = Platform.start();
             VulkanInstance instance = VulkanInstance.builder().validation(true).build()) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            Gpu gpu = instance.bestGpu();
            assumeThat(gpu.supports(VK_KHR_SWAPCHAIN_EXTENSION_NAME)).as("no presentation").isTrue();
            Window first = platform.createWindow(WindowOptions.of("first", 320, 240));
            Window second = platform.createWindow(WindowOptions.of("second", 200, 150));
            try (VulkanDevice device = VulkanDevice.create(gpu);
                 Surface firstSurface = instance.createSurface(first);
                 Surface secondSurface = instance.createSurface(second)) {
                assumeThat(gpu.canPresent(firstSurface, device.graphics().family())).as("cannot present").isTrue();
                assertThat(firstSurface.window()).isSameAs(first);
                List<Swapchain> swapchains = List.of(
                    new Swapchain(device, firstSurface, PresentMode.FIFO, first.pixelWidth(), first.pixelHeight()),
                    new Swapchain(device, secondSurface, PresentMode.MAILBOX, second.pixelWidth(), second.pixelHeight()));
                List<Frames> frames = List.of(new Frames(device, device.graphics(), 2, 2),
                    new Frames(device, device.graphics(), 2, 2));
                Swapchain swapchain = swapchains.getFirst();
                assertThat(swapchain.isUsable()).isTrue();
                assertThat(swapchain.width()).isEqualTo(first.pixelWidth());
                assertThat(swapchain.imageCount()).isGreaterThanOrEqualTo(2);
                assertThat(swapchain.handle()).isNotZero();
                assertThat(swapchain.format()).isNotZero();
                assertThat(frames.getFirst().inFlight()).isEqualTo(2);
                for (int f = 0; f < 10; f++) {
                    render(device, swapchains, frames, f / 10f);
                }
                // Resize at runtime: the window reports its new pixel size, the swapchain is rebuilt for it.
                first.setSize(480, 360);
                first.sync();
                int[] size = new int[2];
                platform.pollEvents(new PlatformListener() {
                    @Override
                    public void windowPixelSizeChanged(int window, int width, int height) {
                        if (window == first.id()) {
                            size[0] = width;
                            size[1] = height;
                        }
                    }
                });
                assertThat(size[0]).isEqualTo(first.pixelWidth());
                swapchain.recreate(size[0], size[1]);
                assertThat(swapchain.width()).isEqualTo(size[0]);
                assertThat(swapchain.height()).isEqualTo(size[1]);
                assertThat(swapchain.needsRecreate()).isFalse();
                for (int f = 0; f < 10; f++) {
                    render(device, swapchains, frames, f / 10f);
                }
                // Switch present modes at runtime; unsupported ones fall back to vsync.
                List<PresentMode> supported = swapchain.supportedPresentModes();
                assertThat(supported).contains(PresentMode.FIFO);
                for (PresentMode mode : PresentMode.values()) {
                    swapchain.setPresentMode(mode);
                    assertThat(swapchain.requestedPresentMode()).isEqualTo(mode);
                    assertThat(swapchain.presentMode()).isEqualTo(supported.contains(mode) ? mode : PresentMode.FIFO);
                    render(device, swapchains, frames, 0.5f);
                }
                for (Frames frame : frames) {
                    frame.close();
                }
                for (Swapchain chain : swapchains) {
                    chain.close();
                    chain.close(); // twice is fine
                    assertThat(chain.isUsable()).isFalse();
                }
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }

    @Test
    void headlessDevicesCannotPresent() {
        try (Platform platform = Platform.start();
             VulkanInstance instance = VulkanInstance.builder().validation(true).build()) {
            assumeThat(instance.gpus()).isNotEmpty();
            Window window = platform.createWindow(WindowOptions.of("headless", 100, 100).withHidden(true));
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
                 Surface surface = instance.createSurface(window)) {
                assertThatThrownBy(() -> new Swapchain(device, surface, PresentMode.FIFO, 100, 100))
                    .isInstanceOf(VulkanException.class).hasMessage("The device was created headless");
                surface.close();
                surface.close(); // twice is fine
                assertThatIllegalStateException().isThrownBy(surface::handle);
            }
        }
    }

    @Test
    void framesRecordAndSubmitWithoutAllocating() {
        try (VulkanInstance instance = headless(); VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             Frames frames = new Frames(device, device.graphics(), 2, 1)) {
            // LWJGL wraps native pointers in MemorySegments, which only C2 compiles away: warm up long enough.
            assertAllocationFree(() -> {
                Frames.Frame frame = frames.next();
                frame.begin(0);
                frame.end(0);
                frame.submit(device.graphics(), 0, false);
            }, 20_000, 1_000, 10);
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
