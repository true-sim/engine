package com.truesim.engine.graph;

import com.truesim.engine.graph.GraphBuilder.PassInfo;
import com.truesim.engine.graph.GraphCompiler.BufferSlot;
import com.truesim.engine.graph.GraphCompiler.HistoryPlan;
import com.truesim.engine.graph.GraphCompiler.Slot;
import java.util.List;

/**
 * A compiled frame graph, made by {@link GraphCompiler} without a device and not changed after: the passes in order and
 * their queues, where each image and buffer lives, the barriers before each pass, how the passes run in batches and
 * chunks, and the histories' layouts at the frame's start and end, for the seam to the graph before.
 *
 * <p>An image lives in physical image {@code targetOf[image]} (the slots' images, then two per history) or, as
 * {@code ~index}, in external image {@code externals[index]}; a buffer in pooled buffer {@code bufferTargetOf[buffer]}
 * or external buffer {@code externalBuffers[~index]}.
 */
record CompiledGraph(PassInfo[] order, int[] queues, int builtPasses, int[] targetOf, List<Slot> imageSlots,
    List<HistoryPlan> histories, ExternalImage[] externals, int[] bufferTargetOf, List<BufferSlot> bufferSlots,
    ExternalBuffer[] externalBuffers, BarrierList barriers, int[] historyStartCurrent, int[] historyStartPrevious,
    int[] historyEndCurrent, Batches batches) {

    /** How many physical images the pool gives: the slots', then each history's two. */
    int physicalCount() {
        return imageSlots.size() + 2 * histories.size();
    }

    /**
     * The barriers, barrier {@code b} of {@code target} (a physical or, as {@code ~index}, external image or buffer, by
     * {@code kind}) with its layouts, stages and accesses: before pass {@code i}, those from {@code start[i]} to
     * {@code start[i + 1]}; the last range is after all passes (the outputs' final layouts). Each covers mip levels
     * {@code baseMip} to {@code baseMip + mipCount - 1} of layers {@code baseLayer} on ({@code -1} counts: the rest).
     */
    record BarrierList(int[] start, int[] target, int[] kind, int[] aspect, int[] oldLayout, int[] newLayout,
        long[] srcStage, long[] srcAccess, long[] dstStage, long[] dstAccess, int[] baseMip, int[] mipCount,
        int[] baseLayer, int[] layerCount) {

        int size() {
            return target.length;
        }
    }

    /**
     * How the passes run: batches, each on one queue, waiting for the other queue's batch of ordinal
     * {@code waitFor[b]} (0: none) and, with {@code acquire[b]}, for the external images; each cut into chunks recorded
     * in parallel, chunk {@code c} being passes {@code chunkFirstPass[c]} to {@code chunkEndPass[c]} of batch
     * {@code chunkBatch[c]}.
     */
    record Batches(int[] queue, int[] ordinal, int[] waitFor, boolean[] acquire, int[] firstChunk,
        int lastGraphicsBatch, int firstGraphicsChunk, int graphicsBatches, int computeBatches, int graphicsChunks, int computeChunks,
        int[] chunkBatch, int[] chunkFirstPass, int[] chunkEndPass) {
    }
}
