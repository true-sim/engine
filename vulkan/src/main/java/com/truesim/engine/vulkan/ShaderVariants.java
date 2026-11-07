package com.truesim.engine.vulkan;

import com.truesim.annotations.AllowAllocation;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which build of each shader a device loads: a library's shader rebuilt by a later project (with that project's
 * shading hooks, say) replaces the library's own wherever {@link ShaderModule#load} is asked for it, so the code
 * making pipelines names the shader as always. What installed the replacements may be kept beside them by type
 * ({@link #attach}), for code needing to know how the shaders it loads were built. Thread-safe.
 *
 * <p>Replacements come before the first shader: {@link #replace} throws once the device loaded one, so no pipeline is
 * ever made from a shader that a replacement would have changed. The {@link Installer}s found on the classpath
 * ({@link ServiceLoader}) install theirs just before the first shader loads; code may install more before that.
 *
 * <pre>{@code
 * device.shaderVariants().replace("shaders/ao/ao.spv", "shaders/shading-hooks/GameHooks/ao/ao.hooks.spv");
 * }</pre>
 */
@AllowAllocation("installed once, before rendering")
public final class ShaderVariants {

    /**
     * Installs replacements on a device's variants before its first shader loads: a service
     * ({@code META-INF/services/com.truesim.engine.vulkan.ShaderVariants$Installer}), e.g. engine-render's shading
     * hooks choosing the listing whose variants hold every hook on the classpath.
     */
    public interface Installer {

        /** Installs on {@code variants}, which have every replacement made so far (none: {@link #replace}). */
        void install(ShaderVariants variants);
    }

    private final Map<String, String> replaced = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> attached = new ConcurrentHashMap<>();
    private volatile boolean loaded; // a shader was loaded: no more replacements
    private boolean installing;

    ShaderVariants() {
    }

    /**
     * Loads classpath resource {@code variant} wherever {@code resource} is asked for; returns this.
     *
     * @throws IllegalStateException once the device loaded a shader
     */
    public synchronized ShaderVariants replace(String resource, String variant) {
        if (loaded) {
            throw new IllegalStateException("The device loaded shaders already: " + resource + "'s variant "
                + variant + " would leave pipelines made before it without what it changes; replace shaders before "
                + "the first loads");
        }
        replaced.put(Objects.requireNonNull(resource, "resource"), Objects.requireNonNull(variant, "variant"));
        return this;
    }

    /**
     * The resource loaded for {@code resource}: its replacement, or itself. The first call runs the classpath's
     * {@link Installer}s and closes the variants to replacements. Allocates nothing after it.
     */
    public String resolve(String resource) {
        if (!loaded) {
            install();
        }
        String variant = replaced.get(resource);
        return variant != null ? variant : resource;
    }

    /** Runs the classpath's installers once, then takes no more replacements. */
    private synchronized void install() {
        if (loaded || installing) {
            return;
        }
        installing = true;
        try {
            for (Installer installer : ServiceLoader.load(Installer.class, ShaderVariants.class.getClassLoader())) {
                installer.install(this);
            }
        } finally {
            installing = false;
            loaded = true;
        }
    }

    /** Whether the device loaded a shader: replacements are closed. */
    public boolean closed() {
        return loaded;
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
