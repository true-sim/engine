package com.truesim.engine.graph;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;

/** What becomes of an attachment when its pass stops rendering: kept ({@link #STORE}) or not needed after. */
public enum Store {
    STORE(VK_ATTACHMENT_STORE_OP_STORE),
    DONT_CARE(VK_ATTACHMENT_STORE_OP_DONT_CARE),
    /** Nothing done: an attachment only read (a depth tested, not written). */
    NONE(org.lwjgl.vulkan.VK13.VK_ATTACHMENT_STORE_OP_NONE);

    final int op;

    Store(int op) {
        this.op = op;
    }
}
