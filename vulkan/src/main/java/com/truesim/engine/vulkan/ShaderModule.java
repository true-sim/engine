package com.truesim.engine.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

/**
 * A SPIR-V module on a device, with all the entry points its Slang source declared (named as in the source). Shaders
 * are written in Slang under {@code src/main/slang} and compiled by build-logic's {@code truesim.slang} plugin to
 * classpath resources under {@code shaders/}.
 *
 * <pre>{@code
 * try (ShaderModule meshlets = ShaderModule.load(device, "shaders/meshlets.spv")) {
 *     Pipeline cull = Pipeline.compute(device, meshlets, "cullMain", PUSH_BYTES);
 * }
 * }</pre>
 */
@AllowAllocation("created rarely")
public final class ShaderModule implements AutoCloseable {

    private final VulkanDevice device;
    private final long handle;
    private final String name;
    private final int[] constantIds;
    private boolean closed;

    private ShaderModule(VulkanDevice device, ByteBuffer code, String name) {
        this.device = device;
        this.name = name;
        this.constantIds = constantIds(code);
        try (MemoryStack stack = stackPush()) {
            LongBuffer module = stack.mallocLong(1);
            Vulkan.check(vkCreateShaderModule(device.handle(), VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(code), null, module), "Could not create the shader module " + name);
            this.handle = module.get(0);
        }
    }

    /**
     * The SPIR-V at classpath resource {@code resource}, e.g. {@code shaders/meshlets.spv}, or at its replacement on
     * the device ({@link VulkanDevice#shaderVariants}).
     */
    public static ShaderModule load(VulkanDevice device, String resource) {
        resource = device.shaderVariants().resolve(resource);
        byte[] bytes;
        try (InputStream in = ShaderModule.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No shader " + resource + " on the classpath");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the shader " + resource, e);
        }
        return of(device, bytes, resource);
    }

    /** A module from SPIR-V {@code code}, named {@code name} in errors. */
    public static ShaderModule of(VulkanDevice device, byte[] code, String name) {
        if (code.length == 0 || code.length % 4 != 0) {
            throw new IllegalArgumentException("Not SPIR-V: " + name + " has " + code.length + " bytes");
        }
        ByteBuffer spirv = MemoryUtil.memAlloc(code.length).put(code).flip();
        try {
            return new ShaderModule(device, spirv, name);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    /**
     * The ids of the specialization constants the module declares ({@code SpecId} decorations), sorted: what
     * {@link Specialization} checks a pipeline sets.
     */
    public int[] constantIds() {
        return constantIds.clone();
    }

    /** The {@code SpecId} decorations' ids of SPIR-V {@code code} (little-endian words, after the 5-word header). */
    private static int[] constantIds(ByteBuffer code) {
        java.nio.IntBuffer words = code.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        int at = 5;
        int n = words.limit();
        while (at < n) {
            int word = words.get(at);
            int count = word >>> 16;
            int opcode = word & 0xffff;
            if (count == 0) {
                break; // not SPIR-V past here
            }
            if (opcode == OP_DECORATE && count >= 4 && words.get(at + 2) == DECORATION_SPEC_ID) {
                ids.add(words.get(at + 3));
            }
            at += count;
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final int OP_DECORATE = 71;
    private static final int DECORATION_SPEC_ID = 1;

    public long handle() {
        return handle;
    }

    public String name() {
        return name;
    }

    /** Destroys the module; pipelines made from it keep working. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            vkDestroyShaderModule(device.handle(), handle, null);
        }
    }
}
