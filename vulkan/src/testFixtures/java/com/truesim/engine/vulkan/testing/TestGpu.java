package com.truesim.engine.vulkan.testing;

import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/**
 * A headless, validated GPU for tests in any repo (the engine's, effects-*, plugins-*, samples): a Vulkan instance
 * without surfaces and with the validation layer, its best GPU's device, and a check that nothing the test did made
 * the layer report an error. Where there is no Vulkan 1.3 GPU at all the test is skipped (aborted, not failed);
 * with {@code TRUESIM_REQUIRE_VALIDATION=true} (CI) a missing validation layer fails it instead of passing silently.
 *
 * <pre>{@code
 * private TestGpu gpu;
 *
 * @BeforeEach void start() { gpu = TestGpu.open(); }               // skips the test without a GPU
 * @AfterEach void stop() { gpu.close(); }                          // fails it on validation errors
 *
 * @Test void drawsSomething() {
 *     try (GpuImage target = new GpuImage(gpu.device(), ...)) { ... }
 * }
 * }</pre>
 *
 * <p>Resources the test closes itself go before {@link #close()}; {@link #closing} hands it others to close first, in
 * reverse order, after the device is idle.
 */
public final class TestGpu implements AutoCloseable {

    /** The environment variable that makes a missing validation layer fail a test (CI sets it). */
    public static final String REQUIRE_VALIDATION = "TRUESIM_REQUIRE_VALIDATION";

    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final List<AutoCloseable> owned = new ArrayList<>();
    private boolean closed;

    private TestGpu(VulkanInstance instance, VulkanDevice device) {
        this.instance = instance;
        this.device = device;
    }

    /** A validated headless GPU, or the test skipped without one. */
    public static TestGpu open() {
        return open(false);
    }

    /**
     * A validated headless GPU with synchronization validation too ({@code synchronization}): slower, for tests of
     * barriers and passes. The test is skipped without a GPU.
     */
    public static TestGpu open(boolean synchronization) {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(synchronization).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            Assumptions.abort("no Vulkan available: " + e.getMessage());
            throw e;
        }
        if (instance.gpus().isEmpty()) {
            instance.close();
            Assumptions.abort("no Vulkan 1.3 GPU");
        }
        if ("true".equals(System.getenv(REQUIRE_VALIDATION)) && !instance.validationEnabled()) {
            instance.close();
            throw new AssertionError("Validation is required (" + REQUIRE_VALIDATION + ") but the layer is not active");
        }
        try {
            return new TestGpu(instance, VulkanDevice.createHeadless(instance.bestGpu()));
        } catch (RuntimeException e) {
            instance.close();
            throw e;
        }
    }

    /** The instance. */
    public VulkanInstance instance() {
        return instance;
    }

    /** The device: the instance's best GPU, headless. */
    public VulkanDevice device() {
        return device;
    }

    /** Validation errors reported so far. */
    public int validationErrors() {
        return instance.validationErrors();
    }

    /** Fails if the validation layer reported an error so far; {@code what} says when. */
    public void assertNoValidationErrors(String what) {
        int errors = instance.validationErrors();
        if (errors != 0) {
            throw new AssertionError(errors + " validation errors " + what + " (the log has them)");
        }
    }

    /** Closes {@code resource} on {@link #close()}, before the device, after those handed earlier. Returns it. */
    public <T extends AutoCloseable> T closing(T resource) {
        owned.add(resource);
        return resource;
    }

    /**
     * Waits for the device, closes what {@link #closing} was given (last first), the device and the instance, then
     * fails if the validation layer reported an error during the test.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        int errors;
        try {
            device.waitIdle();
            for (int i = owned.size() - 1; i >= 0; i--) {
                owned.get(i).close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("closing a test's GPU resources", e);
        } finally {
            errors = instance.validationErrors();
            device.close();
            instance.close();
        }
        if (errors != 0) {
            throw new AssertionError(errors + " validation errors during the test (the log has them)");
        }
    }
}
