package com.truesim.engine.render;

import com.truesim.engine.vulkan.DeviceBuffer;

/** Takes a material record's buffer fields ({@code T*} in Slang): where each lies in the block, and its buffer. */
@FunctionalInterface
public interface MaterialBuffers {

    /** The field at byte {@code offset} of the material's block holds {@code buffer}, or null for none. */
    void accept(long offset, DeviceBuffer buffer);
}
