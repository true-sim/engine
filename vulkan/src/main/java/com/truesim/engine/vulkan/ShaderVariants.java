package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which build of each shader a device loads: a library's shader rebuilt by a later project (with that project's
 * shading hooks, say) replaces the library's own wherever {@link ShaderModule#load} is asked for it, so the code
 * making pipelines names the shader as always. What installed the replacements may be kept beside them by type
 * ({@link #attach}), for code needing to know how the shaders it loads were built. Thread-safe.
 *
 * <p>Pipelines made before a replacement keep the shader they were made with: install replacements before the first
 * frame.
 *
 * <pre>{@code
 * device.shaderVariants().replace("shaders/ao/ao.spv", "shaders/shading-hooks/GameHooks/ao/ao.hooks.spv");
 * }</pre>
 */
@AllowAllocation("installed once, before rendering")
public final class ShaderVariants {

    private final Map<String, String> replaced = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> attached = new ConcurrentHashMap<>();

    ShaderVariants() {
    }

    /** Loads classpath resource {@code variant} wherever {@code resource} is asked for; returns this. */
    public ShaderVariants replace(String resource, String variant) {
        replaced.put(Objects.requireNonNull(resource, "resource"), Objects.requireNonNull(variant, "variant"));
        return this;
    }

    /** The resource loaded for {@code resource}: its replacement, or itself. Allocates nothing. */
    public String resolve(String resource) {
        String variant = replaced.get(resource);
        return variant != null ? variant : resource;
    }

    /** Keeps {@code value} as what installed replacements of its kind, for {@link #attached}; returns this. */
    public <T> ShaderVariants attach(Class<T> type, T value) {
        attached.put(Objects.requireNonNull(type, "type"), type.cast(Objects.requireNonNull(value, "value")));
        return this;
    }

    /** What {@link #attach} kept for {@code type}, or null. Allocates nothing. */
    public <T> T attached(Class<T> type) {
        return type.cast(attached.get(type));
    }
}
