package com.truesim.engine.graph;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDEX_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_PRE_RASTERIZATION_SHADERS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/**
 * How a pass uses a {@link Buffer}: the pipeline stages and memory accesses, and whether its contents change. Shaders
 * reach buffers by device address; that is a storage access. The graph derives every barrier from these.
 */
public enum BufferAccess {

    /** Read by indirect draws or dispatches. */
    INDIRECT(VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT, VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT, false,
        VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, false),
    /** Bound as the index buffer of draws. */
    INDEX(VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT, VK_ACCESS_2_INDEX_READ_BIT, false, VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
        true),
    /** Read by compute shaders. */
    STORAGE_READ(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT, false,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false),
    /** Written (and possibly read) by compute shaders. */
    STORAGE_WRITE(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, true,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false),
    /** Read by the shaders of draws: vertex, task, mesh or fragment. */
    DRAW_READ(VK_PIPELINE_STAGE_2_PRE_RASTERIZATION_SHADERS_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
        VK_ACCESS_2_SHADER_STORAGE_READ_BIT, false, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true),
    /** Written (and possibly read) by the shaders of draws. */
    DRAW_WRITE(VK_PIPELINE_STAGE_2_PRE_RASTERIZATION_SHADERS_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
        VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, true,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true),
    /** The source of a copy. */
    TRANSFER_SRC(VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT, false,
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false),
    /** The destination of a copy, fill or update. */
    TRANSFER_DST(VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, true,
        VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);

    final long stages;
    final long access;
    final boolean writes;
    final int usage;
    final boolean draws;

    BufferAccess(long stages, long access, boolean writes, int usage, boolean draws) {
        this.stages = stages;
        this.access = access;
        this.writes = writes;
        this.usage = usage;
        this.draws = draws;
    }

    /** Whether the pass changes the buffer. */
    public boolean writes() {
        return writes;
    }
}
