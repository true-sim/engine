package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

import org.junit.jupiter.api.Test;

/** The device's bindless set: slots taken lowest first, freed for reuse, never more than there are; validated. */
class BindlessTest {

    @Test
    void slotsAreTakenLowestFirstAndReused() {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
                 GpuImage image = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM,
                     VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
                 GpuImage copyOnly = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM,
                     VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
                Bindless bindless = device.bindless();
                assertThat(bindless.pipelineLayout()).isNotZero();
                assertThat(bindless.set()).isNotZero();
                assertThat(copyOnly.view()).as("no view for an image only copied to").isZero();
                // Mip levels: a view of each for storage, the whole chain for sampling.
                assertThat(GpuImage.fullMipCount(13, 7)).isEqualTo(4);
                try (GpuImage pyramid = GpuImage.mipmapped(device, 13, 7, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT, VK_IMAGE_ASPECT_COLOR_BIT, 4)) {
                    assertThat(pyramid.mips()).isEqualTo(4);
                    assertThat(pyramid.view(3)).isNotZero().isNotEqualTo(pyramid.view(0)).isNotEqualTo(pyramid.view());
                    int slot = bindless.addStorage(pyramid.view(3));
                    bindless.removeStorage(slot);
                }
                assertThat(image.view(0)).isEqualTo(image.view());
                assertThatIllegalArgumentException().isThrownBy(() -> GpuImage.mipmapped(device, 13, 7,
                    VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, 5));
                if (!device.rayTracing()) {
                    assertThatIllegalStateException().isThrownBy(() -> bindless.addAccelerationStructure(1))
                        .withMessageContaining("no ray tracing");
                }
                // Bound on a compute-only queue: no graphics bind point there.
                device.submitAndWait(device.compute(), commands -> bindless.bind(commands, false));
                if (device.compute().family() != device.graphics().family()) {
                    try (GpuImage shared = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, device.graphics().family(),
                        device.compute().family())) {
                        assertThat(shared.view()).isNotZero();
                    }
                }
                assertThat(bindless.addSampled(image.view())).isZero();
                assertThat(bindless.addSampled(image.view())).isEqualTo(1);
                assertThat(bindless.addStorage(image.view())).isZero();
                bindless.removeSampled(0);
                assertThat(bindless.addSampled(image.view())).isZero(); // the lowest free slot again
                // Removed while frames may sample it: taken until they finish.
                FrameSlots slots = new FrameSlots(2);
                try (Retiring retiring = new Retiring(slots)) {
                    slots.begin(0);
                    bindless.removeSampled(0, retiring);
                    bindless.removeStorage(0, retiring);
                    assertThatIllegalArgumentException().isThrownBy(() -> bindless.removeSampled(9, retiring));
                    slots.end();
                    assertThat(bindless.addSampled(image.view())).as("still in flight").isEqualTo(2);
                    slots.begin(1);
                    slots.end();
                    slots.begin(0);
                    retiring.release();
                    slots.end();
                    assertThat(bindless.addSampled(image.view())).as("free once its frames finished").isZero();
                    bindless.removeSampled(2);
                    assertThat(bindless.addStorage(image.view())).isZero();
                }
                assertThat(bindless.slotsInUse()).isEqualTo(3);
                assertThatIllegalArgumentException().isThrownBy(() -> bindless.removeStorage(5));
                assertThatIllegalArgumentException().isThrownBy(() -> bindless.removeStorage(-1));
                assertThatIllegalArgumentException().isThrownBy(() -> bindless.removeStorage(Bindless.STORAGE_SLOTS));
                for (int i = 1; i < Bindless.STORAGE_SLOTS; i++) {
                    bindless.addStorage(image.view());
                }
                assertThatIllegalStateException().isThrownBy(() -> bindless.addStorage(image.view()))
                    .withMessageContaining("storage slots are taken");
                for (int i = 0; i < Bindless.STORAGE_SLOTS; i++) {
                    bindless.removeStorage(i);
                }
                bindless.removeSampled(0);
                bindless.removeSampled(1);
                assertThat(bindless.slotsInUse()).isZero();
                assertThatIllegalArgumentException().isThrownBy(() -> Pipeline.compute(device, null, "main",
                    Bindless.PUSH_BYTES + 4)).withMessageContaining("Push constants");
                device.waitIdle();
                image.close();
                image.close(); // twice is fine
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
