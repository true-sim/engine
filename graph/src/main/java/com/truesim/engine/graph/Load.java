package com.truesim.engine.graph;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;

/**
 * What an attachment holds when its pass starts rendering: what it held ({@link #LOAD}), its {@link Clear} value, or
 * anything ({@link #DONT_CARE}, for an attachment the pass covers entirely). Neither of the last two needs the earlier
 * contents, so the graph discards them: the image starts from an undefined layout.
 */
public enum Load {
    LOAD(VK_ATTACHMENT_LOAD_OP_LOAD),
    CLEAR(VK_ATTACHMENT_LOAD_OP_CLEAR),
    DONT_CARE(VK_ATTACHMENT_LOAD_OP_DONT_CARE);

    final int op;

    Load(int op) {
        this.op = op;
    }
}
