package com.truesim.engine.render;

/**
 * A project's shading hooks as the build listed them: the class truesim.slang generates from engine-render's
 * shading-hooks templates (its own hooks and its libraries'), found as a service on the classpath. The engine takes the
 * listing holding every other one's hooks ({@link ShadingHooks#of(com.truesim.engine.vulkan.VulkanDevice)}).
 */
public interface ShadingHookListing {

    /** The hooks, with their parameters' records and the variants of the libraries' shaders rebuilt with them. */
    ShadingHooks hooks();
}
