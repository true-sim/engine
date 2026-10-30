package com.truesim.engine.vulkan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The device's pipeline cache: pipelines are made through it, and it survives runs on disk, from this GPU only. */
class PipelineCacheTest {

    @TempDir
    Path dir;

    @Test
    void theCacheIsSavedAndLoadedForThisGpuOnly() throws Exception {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        Path file = dir.resolve("cache").resolve("pipelines.bin");
        try (instance) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            byte[] saved;
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                PipelineCache cache = device.pipelineCache();
                assertThat(cache.handle()).isNotZero();
                cache.persist(file); // nothing there yet: nothing loaded
                try (ShaderModule shader = ShaderModule.load(device, "shaders/test/squares.spv");
                     Pipeline pipeline = Pipeline.compute(device, shader, "squaresMain", 8)) {
                    assertThat(pipeline.handle()).isNotZero();
                }
                saved = cache.data();
                assertThat(cache.fits(saved)).isTrue();
                assertThat(cache.load(saved)).isTrue();
                // Another GPU's, or damaged: ignored.
                byte[] other = saved.clone();
                ByteBuffer.wrap(other).order(ByteOrder.LITTLE_ENDIAN).putInt(8, ~ByteBuffer.wrap(saved)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt(8));
                assertThat(cache.load(other)).isFalse();
                other = saved.clone();
                other[20] ^= 1; // the cache UUID
                assertThat(cache.load(other)).isFalse();
                assertThat(cache.load(new byte[8])).isFalse();
                other = saved.clone();
                other[4] = 2; // a header version not known
                assertThat(cache.load(other)).isFalse();
            } // saved on close
            assertThat(Files.size(file)).isPositive();
            assertThat(Files.list(file.getParent())).containsExactly(file); // no temporary file left
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                device.pipelineCache().persist(file); // loads it
                assertThat(device.pipelineCache().fits(Files.readAllBytes(file))).isTrue();
                Files.write(file, new byte[] {1, 2, 3}); // replaced on close
            }
            assertThat(Files.size(file)).isGreaterThan(3);
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                device.pipelineCache().persist(file);
                Files.delete(file);
                Files.createDirectory(file); // cannot be saved over: logged, not thrown
            }
            assertThat(instance.validationErrors()).isZero();
        }
    }
}
