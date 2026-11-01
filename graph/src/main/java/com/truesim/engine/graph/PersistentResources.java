package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.Queue;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link FrameGraph}'s persistent images and buffers by key, kept across builds while they match, the ones a rebuild
 * replaced (freed after it), and the buffer histories of the graph built with their views.
 */
@AllowAllocation("set up once")
final class PersistentResources {

    private final VulkanDevice device;
    private final Queue graphicsQueue;
    private final Queue computeQueue;
    private final Map<String, Persistent> persistents = new HashMap<>();
    private final List<Persistent> replaced = new ArrayList<>();
    private Persistent[] bufferHistories = new Persistent[0];
    private ViewPort[] bufferHistoryView = new ViewPort[0];

    PersistentResources(VulkanDevice device, Queue graphicsQueue, Queue computeQueue) {
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
    }

    /**
     * The persistent image ({@code desc}) or {@code count} buffers of {@code size} bytes by {@code key}, for build
     * {@code build}, which frame {@code frame} is the last before: kept from the builds before when they match, else
     * made anew (the ones replaced are freed after the build).
     */
    @AllowAllocation("building the graph, when it changes")
    Persistent get(String key, ImageDesc desc, long size, int count, long frame, long build) {
        Persistent kept = persistents.get(key);
        if (kept != null && !kept.is(desc, size, count)) {
            replaced.add(kept);
            kept = null;
        }
        if (kept == null) {
            kept = desc != null ? new Persistent(device, key, desc)
                : new Persistent(device, key, size, count, graphicsQueue.family(), computeQueue.family());
            kept.firstFrame = frame + 1; // the frame being built is its first
            persistents.put(key, kept);
        }
        kept.built = build;
        return kept;
    }

    /**
     * The persistent resources of build {@code build}, just made by {@code g}: its buffer histories, to swap each
     * frame; those no build asks for any more, and those replaced, are freed by {@code retiring} once no frame in flight
     * uses them.
     */
    @AllowAllocation("building the graph, when it changes")
    void start(GraphBuilder g, long build, Retiring retiring) {
        bufferHistories = new Persistent[g.bufferHistories.size()];
        for (int h = 0; h < bufferHistories.length; h++) {
            bufferHistories[h] = g.bufferHistories.get(h).pair;
        }
        bufferHistoryView = g.bufferHistoryViews.toArray(new ViewPort[0]);
        for (Iterator<Persistent> it = persistents.values().iterator(); it.hasNext();) {
            Persistent kept = it.next();
            if (kept.built != build) {
                it.remove();
                retiring.add(kept);
            }
        }
        for (Persistent old : replaced) {
            retiring.add(old);
        }
        replaced.clear();
    }

    /** How many persistent images and buffers there are. */
    int count() {
        return persistents.size();
    }

    /** Swaps the buffer histories whose view runs this frame. Allocates nothing. */
    void swapHistories() {
        for (int h = 0; h < bufferHistories.length; h++) {
            if (historyRuns(h)) {
                bufferHistories[h].swap();
            }
        }
    }

    /**
     * Frame {@code frame} starts: a buffer history of a view skipped that has not been written yet has its first
     * frame moved on, as the frame after it first is has a last frame. Allocates nothing.
     */
    void startFrame(long frame) {
        for (int h = 0; h < bufferHistories.length; h++) {
            if (!historyRuns(h) && bufferHistories[h].firstFrame >= frame) {
                bufferHistories[h].firstFrame = frame + 1;
            }
        }
    }

    private boolean historyRuns(int h) {
        return bufferHistoryView[h] == null || bufferHistoryView[h].active;
    }

    /** Frees them all; the frames are done. */
    void close() {
        for (Persistent kept : persistents.values()) {
            kept.close();
        }
        persistents.clear();
        for (Persistent old : replaced) {
            old.close();
        }
        replaced.clear();
        bufferHistories = new Persistent[0];
        bufferHistoryView = new ViewPort[0];
    }
}
