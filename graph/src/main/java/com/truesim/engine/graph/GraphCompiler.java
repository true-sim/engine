package com.truesim.engine.graph;

import static com.truesim.engine.graph.FrameGraph.BUFFER;
import static com.truesim.engine.graph.FrameGraph.COMPUTE;
import static com.truesim.engine.graph.FrameGraph.GRAPHICS;
import static com.truesim.engine.graph.FrameGraph.IMAGE;
import static com.truesim.engine.graph.FrameGraph.aspect;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.NodeGraph;
import com.truesim.common.graph.Schedule;
import com.truesim.engine.graph.CompiledGraph.BarrierList;
import com.truesim.engine.graph.CompiledGraph.Batches;
import com.truesim.engine.graph.GraphBuilder.BufferInfo;
import com.truesim.engine.graph.GraphBuilder.ImageInfo;
import com.truesim.engine.graph.GraphBuilder.PassInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Compiles what techniques built into a {@link CompiledGraph}, without a device: outputs drawn into their targets or
 * copied, passes culled and ordered (by common-graph), queues chosen, images and buffers placed into the slots of a
 * pool, histories planned, barriers derived and the passes cut into batches and chunks. The frame graph then gives the
 * slots their images and buffers and records what was compiled.
 */
@AllowAllocation("building the graph, when it changes")
final class GraphCompiler {

    /** Records an output's copy into its target, for outputs not drawn straight into it. */
    @FunctionalInterface
    interface OutputCopy {
        void record(PassContext ctx, Image from, Image to);
    }

    /** A history's planned images: its slot, and the physical indices of its current and previous image. */
    record HistoryPlan(String name, Slot slot, int current, int previous) {
    }

    private GraphCompiler() {
    }

    /**
     * Compiles the build {@code g}, its {@code outputs} into their targets; async passes on the compute queue with
     * {@code asyncCompute}, images used by both queues shared across their families with {@code twoFamilies}, batches
     * cut into chunks of {@code grain} passes.
     */
    static CompiledGraph compile(GraphBuilder g, List<? extends Map.Entry<?, ExternalImage>> outputs, boolean asyncCompute,
        boolean twoFamilies, int grain, OutputCopy copy) {
        List<Image> results = new ArrayList<>();
        for (Map.Entry<?, ExternalImage> output : outputs) {
            results.add(g.output(output.getKey()));
        }
        g.finishOpenPasses(); // the build is done: open passes take what was declared after them
        // An output whose image fits its target (size, format, usage; not sampled or a storage image) is drawn
        // straight into it; others are blitted.
        int[] substitute = new int[g.images.size()];
        Arrays.fill(substitute, -1);
        boolean[] needed = new boolean[g.images.size() + outputs.size()];
        // A result with several targets is copied into each: a target held for a frame must not be drawn into.
        Map<Image, Integer> targetsOf = new java.util.HashMap<>();
        for (Image result : results) {
            targetsOf.merge(result, 1, Integer::sum);
        }
        int k = 0;
        for (Map.Entry<?, ExternalImage> output : outputs) {
            ExternalImage target = output.getValue();
            Image result = results.get(k++);
            ImageInfo info = g.images.get(result.id);
            // A target whose contents outlive the frame (a texture) is copied into, so a frame its view is skipped
            // leaves it as it was: drawn into straight, its passes would discard it.
            boolean copied = g.copied.contains(target);
            boolean fits = !copied && target.initialLayout == VK_IMAGE_LAYOUT_UNDEFINED && targetsOf.get(result) == 1
                && info.external == null && substitute[result.id] < 0 && result.width == target.width
                && result.height == target.height && result.format == target.format && result.desc.flat()
                && result.mips == 1
                && (info.usage & ~target.usage) == 0
                && (info.usage & (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)) == 0; // no slots for externals
            Image external = g.external(target);
            if (fits) {
                substitute = Arrays.copyOf(substitute, g.images.size());
                substitute[result.id] = external.id;
            } else if (!copied) {
                g.pass("output " + target.name)
                    .in(output.getKey() instanceof GraphBuilder.Scoped scoped ? scoped.view() : null) // runs with it
                    .read(result, Access.TRANSFER_SRC)
                    .write(external, Access.TRANSFER_DST)
                    .record(ctx -> copy.record(ctx, result, external));
                g.passes.getLast().into = target;
            }
            needed = Arrays.copyOf(needed, g.images.size());
            needed[fits ? result.id : external.id] = true;
        }
        int imageCount = g.images.size();
        substitute = Arrays.copyOf(substitute, imageCount);
        needed = Arrays.copyOf(needed, imageCount);
        // Culled and ordered by the graph core: walking back from the outputs, a pass is needed if it writes an image or
        // buffer needed after it. Images are its resources 0 to imageCount - 1, buffers the ones after.
        List<PassInfo> passes = g.passes;
        NodeGraph nodes = new NodeGraph();
        for (int r = 0; r < imageCount + g.buffers.size(); r++) {
            nodes.resource();
        }
        for (int image = 0; image < imageCount; image++) {
            if (needed[image]) {
                nodes.keep(image);
            }
        }
        for (int b = 0; b < g.buffers.size(); b++) {
            if (g.buffers.get(b).external != null) {
                nodes.keep(imageCount + b); // outlives the frame: its writes are kept
            }
        }
        for (History history : g.histories) {
            nodes.keep(history.current.id); // the next frame reads it
        }
        for (int image = 0; image < imageCount; image++) {
            ExternalImage external = g.images.get(image).external;
            if (external != null && external.kept) {
                nodes.keep(image); // a persistent image: the next frame reads it
            }
        }
        for (PassInfo pass : passes) {
            int node = nodes.node();
            if (pass.sideEffects) {
                nodes.sideEffects(node);
            }
            for (int a = 0; a < pass.images.length; a++) {
                if (pass.accesses[a].writes) {
                    nodes.write(node, pass.images[a]);
                } else {
                    nodes.read(node, pass.images[a]);
                }
            }
            for (int a = 0; a < pass.buffers.length; a++) {
                if (pass.bufferAccesses[a].writes) {
                    nodes.write(node, imageCount + pass.buffers[a]);
                } else {
                    nodes.read(node, imageCount + pass.buffers[a]);
                }
            }
        }
        Schedule schedule = nodes.compile();
        PassInfo[] newOrder = new PassInfo[schedule.size()];
        for (int p = 0; p < newOrder.length; p++) {
            newOrder[p] = passes.get(schedule.node(p));
        }
        // The queue of each pass: async ones on the compute queue, when async compute is on.
        int[] queues = new int[newOrder.length];
        for (int p = 0; p < newOrder.length; p++) {
            PassInfo pass = newOrder[p];
            if (pass.async) {
                for (int a = 0; a < pass.images.length; a++) {
                    Access access = pass.accesses[a];
                    if (access == Access.COLOR_ATTACHMENT || access == Access.DEPTH_ATTACHMENT
                        || access == Access.DEPTH_TEST || access == Access.SAMPLED_FRAGMENT) {
                        throw new IllegalStateException(pass.name + " is async but uses "
                            + g.images.get(pass.images[a]).image.name + " as " + access
                            + ": async passes compute, sample, store and copy only");
                    }
                    if (g.images.get(pass.images[a]).external != null) {
                        throw new IllegalStateException(pass.name + " is async but uses the external image "
                            + g.images.get(pass.images[a]).image.name);
                    }
                }
                for (int a = 0; a < pass.buffers.length; a++) {
                    ExternalBuffer external = g.buffers.get(pass.buffers[a]).external;
                    if (external != null && twoFamilies && !external.concurrent) {
                        throw new IllegalStateException(pass.name + " is async but uses the external buffer "
                            + external.name + ", which one queue family owns: give it concurrent sharing (a"
                            + " GpuBuffer of the graphics and compute families)");
                    }
                    if (pass.bufferAccesses[a].draws) {
                        throw new IllegalStateException(pass.name + " is async but uses "
                            + g.buffers.get(pass.buffers[a]).buffer.name + " as " + pass.bufferAccesses[a]
                            + ": async passes compute, store and copy only");
                    }
                }
                queues[p] = asyncCompute ? COMPUTE : GRAPHICS;
            }
        }
        // Where images live: external ones (and outputs drawn into their target) in their external image, transient
        // ones in physical images, shared by images of one size and format whose lifetimes do not overlap.
        List<ExternalImage> newExternals = new ArrayList<>();
        int[] newTargetOf = new int[imageCount];
        int[] first = new int[imageCount];
        int[] last = new int[imageCount];
        int[] queueMask = new int[imageCount];
        Arrays.fill(first, -1);
        for (int i = 0; i < newOrder.length; i++) {
            for (int image : newOrder[i].images) {
                if (first[image] < 0) {
                    first[image] = i;
                }
                last[image] = i;
                queueMask[image] |= 1 << queues[i];
            }
        }
        List<Slot> slots = new ArrayList<>();
        for (int image = 0; image < imageCount; image++) {
            newTargetOf[image] = Integer.MIN_VALUE;
        }
        Integer[] byFirstUse = new Integer[imageCount];
        for (int i = 0; i < imageCount; i++) {
            byFirstUse[i] = i;
        }
        Arrays.sort(byFirstUse, (a, b) -> Integer.compare(first[a], first[b]));
        for (int image : byFirstUse) {
            if (first[image] < 0) {
                continue; // culled
            }
            ImageInfo info = g.images.get(image);
            if (info.history != null) {
                continue; // kept across frames: below
            }
            ExternalImage external = info.external != null ? info.external
                : substitute[image] >= 0 ? g.images.get(substitute[image]).external : null;
            if (external != null) {
                if ((info.usage & ~external.usage) != 0) {
                    throw new IllegalStateException("External image " + external.name + " is used beyond the usage "
                        + "it was created with");
                }
                // A texture has a sampled slot, a persistent image a storage one too; other external images have no
                // slots, so neither use is open to them.
                int unslotted = (external.storage < 0 ? VK_IMAGE_USAGE_STORAGE_BIT : 0)
                    | (external.sampled < 0 ? VK_IMAGE_USAGE_SAMPLED_BIT : 0);
                if ((info.usage & unslotted) != 0) {
                    throw new IllegalStateException("External image " + external.name + " is sampled or a storage "
                        + "image; only attachment and transfer uses are supported, sampling a texture, and sampling "
                        + "or storing a persistent image");
                }
                int index = newExternals.indexOf(external);
                if (index < 0) {
                    index = newExternals.size();
                    newExternals.add(external);
                }
                newTargetOf[image] = ~index;
                continue;
            }
            Slot slot = null;
            for (Slot candidate : slots) {
                // Only between images of one queue: sharing memory with work on the other queue would make each wait
                // for the other, and async compute would no longer run beside graphics.
                if (candidate.last < first[image] && candidate.desc.equals(info.image.desc)
                    && candidate.queues == queueMask[image] && queueMask[image] != 3) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                slot = new Slot(info.image.desc);
                slots.add(slot);
            }
            slot.last = last[image];
            slot.usage |= info.usage;
            slot.queues |= queueMask[image];
            newTargetOf[image] = slots.indexOf(slot);
        }
        for (Slot slot : slots) {
            slot.shared = slot.queues == 3 && twoFamilies;
        }
        int slotCount = slots.size();
        // Histories: a pair of images each, after the slots' images; the pool keeps them across builds.
        int historyCount = g.histories.size();
        int[] historyCurrent = new int[historyCount];
        int[] historyPrevious = new int[historyCount];
        int[] previousImages = new int[historyCount];
        List<HistoryPlan> historyPlans = new ArrayList<>();
        for (int h = 0; h < historyCount; h++) {
            History history = g.histories.get(h);
            ImageInfo current = g.images.get(history.current.id);
            ImageInfo previous = g.images.get(history.previous.id);
            Slot slot = new Slot(current.image.desc);
            slot.usage = current.usage | previous.usage;
            slot.shared = (queueMask[current.image.id] | queueMask[previous.image.id]) == 3 && twoFamilies;
            history.index = h;
            historyCurrent[h] = slotCount + 2 * h;
            historyPrevious[h] = slotCount + 2 * h + 1;
            historyPlans.add(new HistoryPlan(history.key, slot, historyCurrent[h], historyPrevious[h]));
            newTargetOf[current.image.id] = historyCurrent[h];
            newTargetOf[previous.image.id] = historyPrevious[h];
            previousImages[h] = previous.image.id;
        }
        int physicalCount = slotCount + 2 * historyCount;
        // Where buffers live: in physical buffers, shared (sized for the largest) by buffers of one queue whose
        // lifetimes do not overlap.
        int bufferCount = g.buffers.size();
        int[] bufferFirst = new int[bufferCount];
        int[] bufferLast = new int[bufferCount];
        int[] bufferQueues = new int[bufferCount];
        Arrays.fill(bufferFirst, -1);
        for (int i = 0; i < newOrder.length; i++) {
            for (int buffer : newOrder[i].buffers) {
                if (bufferFirst[buffer] < 0) {
                    bufferFirst[buffer] = i;
                }
                bufferLast[buffer] = i;
                bufferQueues[buffer] |= 1 << queues[i];
            }
        }
        Integer[] buffersByFirstUse = new Integer[bufferCount];
        for (int i = 0; i < bufferCount; i++) {
            buffersByFirstUse[i] = i;
        }
        Arrays.sort(buffersByFirstUse, (a, b) -> Integer.compare(bufferFirst[a], bufferFirst[b]));
        int[] newBufferTargetOf = new int[bufferCount];
        Arrays.fill(newBufferTargetOf, -1);
        List<BufferSlot> bufferSlots = new ArrayList<>();
        List<ExternalBuffer> newExternalBuffers = new ArrayList<>();
        for (int buffer : buffersByFirstUse) {
            if (bufferFirst[buffer] < 0) {
                continue; // culled
            }
            BufferInfo info = g.buffers.get(buffer);
            if (info.external != null) {
                newBufferTargetOf[buffer] = ~newExternalBuffers.size(); // not pooled: lives where it is
                newExternalBuffers.add(info.external);
                continue;
            }
            BufferSlot slot = null;
            for (BufferSlot candidate : bufferSlots) {
                if (candidate.last < bufferFirst[buffer] && candidate.queues == bufferQueues[buffer]
                    && bufferQueues[buffer] != 3) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                slot = new BufferSlot();
                bufferSlots.add(slot);
            }
            slot.size = Math.max(slot.size, info.buffer.size);
            slot.last = bufferLast[buffer];
            slot.usage |= info.usage;
            slot.queues |= bufferQueues[buffer];
            slot.shared = slot.queues == 3 && twoFamilies;
            newBufferTargetOf[buffer] = bufferSlots.indexOf(slot);
        }
        // Barriers, now that every image has a home.
        // The first walk learns the state a frame ends in, which is the next one's start.
        int[] physicalMips = new int[physicalCount];
        int[] physicalLayers = new int[physicalCount];
        for (int s = 0; s < slotCount; s++) {
            physicalMips[s] = slots.get(s).desc.mips();
            physicalLayers[s] = slots.get(s).desc.layers();
        }
        for (HistoryPlan plan : historyPlans) {
            physicalMips[plan.current()] = physicalMips[plan.previous()] = plan.slot().desc.mips();
            physicalLayers[plan.current()] = physicalLayers[plan.previous()] = plan.slot().desc.layers();
        }
        Barriers barriers = new Barriers(physicalMips, physicalLayers, newExternals, bufferSlots.size(),
            newExternalBuffers.size());
        barriers.histories(historyCurrent, historyPrevious, previousImages);
        barriers.simulate(newOrder, queues, newTargetOf, newBufferTargetOf, g, false);
        barriers.simulate(newOrder, queues, newTargetOf, newBufferTargetOf, g, true);
        int[] startCurrent = barriers.startCurrent;
        int[] startPrevious = barriers.startPrevious;
        int[] endCurrent = new int[historyCount];
        for (int h = 0; h < historyCount; h++) {
            endCurrent[h] = barriers.layoutOf(historyCurrent[h]);
        }
        Batches batches = schedule(newOrder.length, queues, barriers.waitPass, barriers.firstExternalPass, grain);
        return new CompiledGraph(newOrder, queues, passes.size(), newTargetOf, slots, historyPlans,
            newExternals.toArray(new ExternalImage[0]), newBufferTargetOf, bufferSlots,
            newExternalBuffers.toArray(new ExternalBuffer[0]), barriers.store(), startCurrent, startPrevious, endCurrent,
            batches);
    }

    /**
     * Splits the passes into batches: a new one where the queue changes, where a pass waits for more of the other queue
     * than its batch does, and at the first pass using an external image (which waits for it to be acquired), so work
     * before it runs without waiting. Each batch is cut into chunks of {@code grain} passes, recorded in parallel.
     */
    static Batches schedule(int passCount, int[] queues, int[] waitPass, int firstExternalPass, int grain) {
        List<int[]> batches = new ArrayList<>(); // queue, first pass, end pass, wait (other queue's ordinal), acquire
        int[] passBatch = new int[passCount];
        int[] ordinals = new int[2];
        List<Integer> batchOrdinals = new ArrayList<>();
        for (int p = 0; p < passCount; p++) {
            int queue = queues[p];
            int wait = waitPass[p] < 0 ? 0 : batchOrdinals.get(passBatch[waitPass[p]]);
            boolean acquire = p == firstExternalPass;
            int[] current = batches.isEmpty() ? null : batches.getLast();
            if (current == null || current[0] != queue || wait > current[3] || acquire) {
                batches.add(new int[] {queue, p, p, wait, acquire ? 1 : 0});
                batchOrdinals.add(++ordinals[queue]);
                current = batches.getLast();
            }
            current[2] = p + 1;
            passBatch[p] = batches.size() - 1;
        }
        int lastGraphics = -1;
        for (int b = 0; b < batches.size(); b++) {
            lastGraphics = batches.get(b)[0] == GRAPHICS ? b : lastGraphics;
        }
        if (lastGraphics < 0) {
            // Nothing on the graphics queue: an empty batch there still signals and waits as asked.
            batches.add(new int[] {GRAPHICS, passCount, passCount, 0, 0});
            batchOrdinals.add(++ordinals[GRAPHICS]);
            lastGraphics = batches.size() - 1;
        }
        int n = batches.size();
        int[] batchQueue = new int[n];
        int[] batchOrdinal = new int[n];
        int[] batchWait = new int[n];
        boolean[] batchAcquire = new boolean[n];
        int[] batchFirstChunk = new int[n + 1];
        List<int[]> chunks = new ArrayList<>(); // batch, first pass, end pass
        for (int b = 0; b < n; b++) {
            int[] batch = batches.get(b);
            batchQueue[b] = batch[0];
            batchOrdinal[b] = batchOrdinals.get(b);
            batchWait[b] = batch[3];
            batchAcquire[b] = batch[4] != 0;
            batchFirstChunk[b] = chunks.size();
            for (int from = batch[1]; from < batch[2]; from += grain) {
                chunks.add(new int[] {b, from, Math.min(batch[2], from + grain)});
            }
            if (b == lastGraphics && (chunks.isEmpty() || chunks.getLast()[0] != b)) {
                chunks.add(new int[] {b, batch[2], batch[2]}); // only the outputs' final barriers
            }
        }
        batchFirstChunk[n] = chunks.size();
        int[] chunkBatch = new int[chunks.size()];
        int[] chunkFirstPass = new int[chunks.size()];
        int[] chunkEndPass = new int[chunks.size()];
        for (int c = 0; c < chunks.size(); c++) {
            chunkBatch[c] = chunks.get(c)[0];
            chunkFirstPass[c] = chunks.get(c)[1];
            chunkEndPass[c] = chunks.get(c)[2];
        }
        int firstGraphicsChunk = 0;
        while (batchQueue[chunkBatch[firstGraphicsChunk]] != GRAPHICS) {
            firstGraphicsChunk++; // there is one: the last graphics batch has a chunk at least
        }
        int graphicsChunks = 0;
        int computeChunks = 0;
        for (int batch : chunkBatch) {
            if (batchQueue[batch] == GRAPHICS) {
                graphicsChunks++;
            } else {
                computeChunks++;
            }
        }
        return new Batches(batchQueue, batchOrdinal, batchWait, batchAcquire, batchFirstChunk, lastGraphics,
            firstGraphicsChunk, ordinals[GRAPHICS], ordinals[COMPUTE], graphicsChunks, computeChunks, chunkBatch,
            chunkFirstPass, chunkEndPass);
    }


    /** A physical image the pool is to have: a size, a format and every usage of the images sharing it. */
    static final class Slot {
        final ImageDesc desc;
        int usage;
        int queues;
        boolean shared; // used by both queues, of different families: concurrent sharing
        int last;

        Slot(ImageDesc desc) {
            this.desc = desc;
        }
    }

    /** A physical buffer the pool is to have: the largest size and every usage of the buffers sharing it. */
    static final class BufferSlot {
        long size;
        int usage;
        int queues;
        boolean shared;
        int last;
    }


    /**
     * Derives barriers by walking the passes in order and tracking, per cell (a mip level of a layer of a physical image,
     * an external image, a physical or external buffer, whose layout never changes), its layout, the stages and
     * accesses that last wrote it, the stages that read it since, what is already visible, and which queue used it
     * last. A pass's barriers are batched, and the barriers of one access merged into runs of mip levels per layer, or
     * one barrier for the whole range where every layer is alike. Crossing to the other queue, a barrier after all
     * commands follows the batch's semaphore wait. Along the way it notes, per pass, the latest pass of the other queue
     * it depends on (for the batch's wait), and the first pass using an external image (which waits for it to be
     * acquired).
     */
    @AllowAllocation("building the graph, when it changes")
    static final class Barriers {
        final int physicalCount;
        final List<ExternalImage> externalList;
        final int bufferCount;          // pooled buffers, after the images' states
        final int externalBufferCount;  // external buffers, after the pooled ones
        final int[] mipsOf;             // per state: a physical image's mips and layers, 1 for the others
        final int[] layersOf;
        final int[] cellStart;          // per state: its first cell
        final int[] layout;
        final long[] writeStages;
        final long[] writeAccess;
        final long[] readStages;
        final long[] visibleStages;
        final long[] visibleAccess;
        final int[] tenant;
        final int[] lastQueue;
        final int[] lastWritePass;
        final int[] lastWriteQueue;
        final int[][] lastReadPass;
        int[] waitPass = new int[0];
        int firstExternalPass = -1;
        // target, aspect, old layout, new layout, srcStage, srcAccess, dstStage, dstAccess, kind,
        // base mip, mip count, base layer, layer count (VK_REMAINING_*: -1)
        final List<long[]> out = new ArrayList<>();
        int[] historyCurrent = new int[0];
        int[] historyPrevious = new int[0];
        int[] previousImages = new int[0];
        int[] startCurrent = new int[0];
        int[] startPrevious = new int[0];
        final List<Integer> starts = new ArrayList<>();

        /**
         * Barriers for {@code physicalMips.length} physical images of {@code physicalMips} mip levels and
         * {@code physicalLayers} layers each, the external images, and the buffers.
         */
        Barriers(int[] physicalMips, int[] physicalLayers, List<ExternalImage> externalList, int bufferCount,
            int externalBufferCount) {
            this.physicalCount = physicalMips.length;
            this.externalList = externalList;
            this.bufferCount = bufferCount;
            this.externalBufferCount = externalBufferCount;
            int states = physicalCount + externalList.size() + bufferCount + externalBufferCount;
            mipsOf = new int[states];
            layersOf = new int[states];
            cellStart = new int[states + 1];
            for (int i = 0; i < states; i++) {
                mipsOf[i] = i < physicalCount ? physicalMips[i] : 1;
                layersOf[i] = i < physicalCount ? physicalLayers[i] : 1;
                cellStart[i + 1] = cellStart[i] + mipsOf[i] * layersOf[i];
            }
            int n = cellStart[states];
            layout = new int[n];
            writeStages = new long[n];
            writeAccess = new long[n];
            readStages = new long[n];
            visibleStages = new long[n];
            visibleAccess = new long[n];
            tenant = new int[n];
            lastQueue = new int[n];
            lastWritePass = new int[n];
            lastWriteQueue = new int[n];
            lastReadPass = new int[n][2];
            Arrays.fill(lastQueue, -1);
        }

        /** The histories' physical images, by role, and the images of the previous role. */
        void histories(int[] current, int[] previous, int[] images) {
            historyCurrent = current;
            historyPrevious = previous;
            previousImages = images;
            startCurrent = new int[current.length];
            startPrevious = new int[current.length];
        }

        /** The layout physical image {@code physical} is in (its first cell's). */
        int layoutOf(int physical) {
            return layout[cellStart[physical]];
        }

        /** Exchanges what is known of physical images {@code a} and {@code b}, of one descriptor: every cell. */
        private void swapState(int a, int b) {
            for (int c = 0; c < cellStart[a + 1] - cellStart[a]; c++) {
                int i = cellStart[a] + c;
                int j = cellStart[b] + c;
                swap(layout, i, j);
                swap(writeStages, i, j);
                swap(writeAccess, i, j);
                swap(readStages, i, j);
                swap(visibleStages, i, j);
                swap(visibleAccess, i, j);
                swap(lastQueue, i, j);
            }
        }

        private static void swap(int[] array, int a, int b) {
            int t = array[a];
            array[a] = array[b];
            array[b] = t;
        }

        private static void swap(long[] array, int a, int b) {
            long t = array[a];
            array[a] = array[b];
            array[b] = t;
        }

        private int index(int target) {
            return target >= 0 ? target : physicalCount + ~target;
        }

        /** Walks the passes; with {@code emit} off, only to learn the state a frame leaves its images in. */
        void simulate(PassInfo[] passes, int[] queues, int[] targets, int[] bufferTargets, GraphBuilder g,
            boolean emit) {
            for (int e = 0; e < externalList.size(); e++) {
                ExternalImage external = externalList.get(e);
                int i = cellStart[physicalCount + e];
                layout[i] = external.initialLayout;
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT; // whatever came before the frame
                writeAccess[i] = external.initialLayout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_2_MEMORY_WRITE_BIT;
                readStages[i] = 0;
                visibleStages[i] = 0;
                visibleAccess[i] = 0;
                lastQueue[i] = GRAPHICS;
            }
            for (int e = 0; e < externalBufferCount; e++) {
                int i = cellStart[physicalCount + externalList.size() + bufferCount + e];
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT; // whatever came before the frame wrote it
                writeAccess[i] = VK_ACCESS_2_MEMORY_WRITE_BIT;
                readStages[i] = 0;
                visibleStages[i] = 0;
                visibleAccess[i] = 0;
                lastQueue[i] = GRAPHICS;
            }
            Arrays.fill(tenant, -1); // transient contents never outlive their frame
            for (int h = 0; h < historyCurrent.length; h++) {
                if (emit) {
                    // The frame boundary: last frame's current image is this frame's previous one.
                    swapState(historyCurrent[h], historyPrevious[h]);
                    startCurrent[h] = layoutOf(historyCurrent[h]);
                    startPrevious[h] = layoutOf(historyPrevious[h]);
                }
                Arrays.fill(tenant, cellStart[historyPrevious[h]], cellStart[historyPrevious[h] + 1],
                    previousImages[h]); // its contents carry over
            }
            Arrays.fill(lastWritePass, -1);
            for (int[] reads : lastReadPass) {
                Arrays.fill(reads, -1);
            }
            waitPass = new int[passes.length];
            Arrays.fill(waitPass, -1);
            firstExternalPass = -1;
            for (int p = 0; p < passes.length; p++) {
                PassInfo pass = passes[p];
                if (emit) {
                    starts.add(out.size());
                }
                // Per image of the pass, its accesses merged per cell.
                int[] seen = new int[pass.images.length];
                int distinct = 0;
                for (int a = 0; a < pass.images.length; a++) {
                    int image = pass.images[a];
                    boolean repeat = false;
                    for (int s = 0; s < distinct; s++) {
                        repeat |= seen[s] == image;
                    }
                    if (repeat) {
                        continue;
                    }
                    seen[distinct++] = image;
                    int target = targets[image];
                    if (target < 0 && firstExternalPass < 0) {
                        firstExternalPass = p;
                    }
                    imageAccess(pass, p, queues[p], image, target, g, emit);
                }
                // And one per buffer.
                int[] seenBuffers = new int[pass.buffers.length];
                int distinctBuffers = 0;
                for (int a = 0; a < pass.buffers.length; a++) {
                    int buffer = pass.buffers[a];
                    boolean repeat = false;
                    for (int s = 0; s < distinctBuffers; s++) {
                        repeat |= seenBuffers[s] == buffer;
                    }
                    if (repeat) {
                        continue;
                    }
                    seenBuffers[distinctBuffers++] = buffer;
                    long stages = 0;
                    long access = 0;
                    boolean writes = false;
                    for (int b = a; b < pass.buffers.length; b++) {
                        if (pass.buffers[b] == buffer) {
                            stages |= pass.bufferAccesses[b].stages;
                            access |= pass.bufferAccesses[b].access;
                            writes |= pass.bufferAccesses[b].writes;
                        }
                    }
                    int target = bufferTargets[buffer];
                    int state = physicalCount + externalList.size() + (target >= 0 ? target : bufferCount + ~target);
                    long[] barrier = access(pass, p, queues[p], cellStart[state], BUFFER, target, 0, buffer,
                        g.buffers.get(buffer).buffer.name, target >= 0, stages, access, writes, 0, false, emit);
                    if (barrier != null) {
                        out.add(ranged(barrier, 0, -1, 0, -1));
                    }
                }
            }
            if (emit) {
                starts.add(out.size());
                for (int e = 0; e < externalList.size(); e++) {
                    int i = cellStart[physicalCount + e];
                    ExternalImage external = externalList.get(e);
                    // Towards whatever comes after the frame.
                    out.add(new long[] {~e, external.format.aspect(), layout[i], external.finalLayout,
                        writeStages[i] | readStages[i], writeAccess[i], VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                        VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, IMAGE, 0, -1, 0, -1});
                }
            }
        }

        /**
         * Pass {@code p}'s accesses to {@code image} (living at barrier target {@code target}), merged per cell: one
         * layout per cell, the stages and accesses of every access covering it. Each touched cell is accessed, and the
         * barriers merged into runs.
         */
        private void imageAccess(PassInfo pass, int p, int queue, int image, int target, GraphBuilder g,
            boolean emit) {
            int state = index(target);
            int mips = mipsOf[state];
            int layers = layersOf[state];
            int cells = mips * layers;
            long[] stages = new long[cells];
            long[] accesses = new long[cells];
            boolean[] writes = new boolean[cells];
            int[] wanted = new int[cells];
            Access[] first = new Access[cells];
            boolean[] discard = new boolean[cells]; // every access of the cell discards its contents
            for (int a = 0; a < pass.images.length; a++) {
                if (pass.images[a] != image) {
                    continue;
                }
                Access access = pass.accesses[a];
                int baseMip = target >= 0 ? pass.ranges[4 * a] : 0; // external images: one cell
                int mipCount = target >= 0 ? pass.ranges[4 * a + 1] : 1;
                int baseLayer = target >= 0 ? pass.ranges[4 * a + 2] : 0;
                int layerCount = target >= 0 ? pass.ranges[4 * a + 3] : 1;
                for (int layer = baseLayer; layer < baseLayer + layerCount; layer++) {
                    for (int mip = baseMip; mip < baseMip + mipCount; mip++) {
                        int c = layer * mips + mip;
                        if (first[c] != null && wanted[c] != access.layout) {
                            throw new IllegalStateException(pass.name + " uses " + g.images.get(image).image.name
                                + " in two layouts: " + first[c] + " and " + access);
                        }
                        discard[c] = (first[c] == null || discard[c]) && pass.discards[a];
                        first[c] = first[c] == null ? access : first[c];
                        wanted[c] = access.layout;
                        stages[c] |= access.stages;
                        accesses[c] |= access.access;
                        writes[c] |= access.writes;
                    }
                }
            }
            Image info = g.images.get(image).image;
            long[][] barriers = new long[cells][];
            for (int c = 0; c < cells; c++) {
                if (first[c] != null) {
                    barriers[c] = access(pass, p, queue, cellStart[state] + c, IMAGE, target, info.format.aspect(),
                        image, info.name, target >= 0, stages[c], accesses[c], writes[c], wanted[c], discard[c], emit);
                }
            }
            emitRuns(barriers, mips, layers);
        }

        /**
         * Adds the barriers of one access, cell by cell, merged: a barrier for a whole range where every layer has the
         * same barrier over one run of mip levels, else one per run of alike mip levels per layer.
         */
        private void emitRuns(long[][] barriers, int mips, int layers) {
            List<long[]> runs = new ArrayList<>();
            boolean uniform = true;
            for (int layer = 0; layer < layers; layer++) {
                int layerRuns = 0;
                for (int mip = 0; mip < mips; mip++) {
                    long[] barrier = barriers[layer * mips + mip];
                    if (barrier == null) {
                        continue;
                    }
                    long[] last = runs.isEmpty() ? null : runs.getLast();
                    if (last != null && last[11] == layer && last[9] + last[10] == mip && alike(last, barrier)) {
                        last[10]++;
                    } else {
                        runs.add(ranged(barrier, mip, 1, layer, 1));
                        layerRuns++;
                    }
                }
                uniform &= layerRuns <= 1;
            }
            if (uniform && runs.size() > 1) {
                long[] firstRun = runs.getFirst();
                boolean same = true;
                for (int r = 1; r < runs.size(); r++) {
                    long[] run = runs.get(r);
                    same &= alike(firstRun, run) && run[9] == firstRun[9] && run[10] == firstRun[10]
                        && run[11] == firstRun[11] + r;
                }
                if (same) {
                    firstRun[12] = runs.size();
                    out.add(firstRun);
                    return;
                }
            }
            out.addAll(runs);
        }

        /** Whether two barriers differ only in their range. */
        private static boolean alike(long[] a, long[] b) {
            for (int f = 0; f < 9; f++) {
                if (a[f] != b[f]) {
                    return false;
                }
            }
            return true;
        }

        /** {@code barrier}'s nine fields with a range: base mip, mip count, base layer, layer count. */
        private static long[] ranged(long[] barrier, int baseMip, int mipCount, int baseLayer, int layerCount) {
            long[] ranged = Arrays.copyOf(barrier, 13);
            ranged[9] = baseMip;
            ranged[10] = mipCount;
            ranged[11] = baseLayer;
            ranged[12] = layerCount;
            return ranged;
        }

        /**
         * Pass {@code p}'s merged access to cell {@code i} of the image or buffer {@code id} ({@code name}), at barrier
         * target {@code target} of {@code kind}; {@code pooled}: in a physical image or buffer, whose earlier tenants
         * it waits for; {@code discard}: the pass needs none of its contents (an attachment cleared or not loaded), so it
         * starts from an undefined layout. Returns the barrier it needs (its nine fields, without a range), or null.
         */
        private long[] access(PassInfo pass, int p, int queue, int i, int kind, int target, int aspect, int id,
            String name, boolean pooled, long stages, long access, boolean writes, int wanted, boolean discard,
            boolean emit) {
            if (lastQueue[i] >= 0 && lastQueue[i] != queue) {
                // From the other queue: submitted, its batch's semaphore wait orders and makes visible what came before;
                // recorded into one command buffer, nothing does. A barrier after all commands, making any write
                // visible, is right either way: this queue's stages cannot be named for the other's work.
                writeStages[i] = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
                writeAccess[i] = writeAccess[i] != 0 ? VK_ACCESS_2_MEMORY_WRITE_BIT : 0;
                readStages[i] = 0;
                visibleStages[i] = 0;
                visibleAccess[i] = 0;
            }
            lastQueue[i] = queue;
            boolean newTenant = pooled && tenant[i] != id;
            boolean transition = layout[i] != wanted;
            if (newTenant || writes || transition) {
                depend(p, Math.max(otherQueue(lastWriteQueue[i], queue, lastWritePass[i]),
                    lastReadPass[i][1 - queue]));
                lastWritePass[i] = p;
                lastWriteQueue[i] = queue;
                lastReadPass[i][0] = -1;
                lastReadPass[i][1] = -1;
            } else {
                depend(p, otherQueue(lastWriteQueue[i], queue, lastWritePass[i]));
                lastReadPass[i][queue] = p;
            }
            long[] barrier = null;
            if (newTenant) {
                // A new image in this physical one: its contents start undefined, after whatever used it before.
                if (!writes) {
                    throw new IllegalStateException(pass.name + " reads " + name + " before any pass writes it");
                }
                long src = writeStages[i] | readStages[i];
                if (emit && (kind == IMAGE || src != 0)) { // a buffer has no layout to start
                    barrier = new long[] {target, aspect, VK_IMAGE_LAYOUT_UNDEFINED, wanted, src, writeAccess[i],
                        stages, access, kind};
                }
                tenant[i] = id;
                set(i, wanted, stages, access, 0); // written: nothing sees it before a barrier
                return barrier;
            }
            if (writes || transition) {
                long src = writeStages[i] | readStages[i];
                if (emit && (src != 0 || transition)) {
                    barrier = new long[] {target, aspect, discard && writes ? VK_IMAGE_LAYOUT_UNDEFINED : layout[i], wanted,
                        src, writeAccess[i], stages, access, kind};
                }
                if (writes) {
                    set(i, wanted, stages, access, 0); // written: nothing sees it before a barrier
                } else {
                    // A transition orders like a write: later readers wait for it, later writers for this pass.
                    set(i, wanted, stages, VK_ACCESS_2_MEMORY_WRITE_BIT, access);
                    readStages[i] = stages;
                }
                return barrier;
            }
            // Read after write, in the same layout: one barrier per new reading stage or access, none after that.
            if ((stages & ~visibleStages[i]) != 0 || (access & ~visibleAccess[i]) != 0) {
                if (emit && writeStages[i] != 0) {
                    barrier = new long[] {target, aspect, wanted, wanted, writeStages[i], writeAccess[i], stages,
                        access, kind};
                }
                visibleStages[i] |= stages;
                visibleAccess[i] |= access;
            }
            readStages[i] |= stages;
            return barrier;
        }

        /** {@code pass}, if it ran on another queue than {@code queue}; else -1. */
        private int otherQueue(int passQueue, int queue, int pass) {
            return pass >= 0 && passQueue != queue ? pass : -1;
        }

        private void depend(int p, int on) {
            waitPass[p] = Math.max(waitPass[p], on);
        }

        /**
         * After a write (or transition) by {@code stages}: visible to those stages and {@code visible} accesses. A
         * write is visible to nothing until a barrier makes it so, even to its own stage and access kind in a later
         * pass (a storage write's access includes reads); a transition is a barrier, visible to the accesses it waits
         * for.
         */
        private void set(int i, int newLayout, long stages, long access, long visible) {
            layout[i] = newLayout;
            writeStages[i] = stages;
            writeAccess[i] = access;
            readStages[i] = 0;
            visibleStages[i] = visible == 0 ? 0 : stages;
            visibleAccess[i] = visible;
        }

        /** The barriers derived, as arrays. */
        BarrierList store() {
            int n = out.size();
            int[] barrierStart = new int[starts.size() + 1];
            for (int s = 0; s < starts.size(); s++) {
                barrierStart[s] = starts.get(s);
            }
            barrierStart[starts.size()] = n;
            int[] barrierTarget = new int[n];
            int[] barrierKind = new int[n];
            int[] barrierAspect = new int[n];
            int[] barrierOldLayout = new int[n];
            int[] barrierNewLayout = new int[n];
            long[] barrierSrcStage = new long[n];
            long[] barrierSrcAccess = new long[n];
            long[] barrierDstStage = new long[n];
            long[] barrierDstAccess = new long[n];
            int[] baseMip = new int[n];
            int[] mipCount = new int[n];
            int[] baseLayer = new int[n];
            int[] layerCount = new int[n];
            for (int b = 0; b < n; b++) {
                long[] barrier = out.get(b);
                barrierTarget[b] = (int) barrier[0];
                barrierKind[b] = (int) barrier[8];
                barrierAspect[b] = (int) barrier[1];
                barrierOldLayout[b] = (int) barrier[2];
                barrierNewLayout[b] = (int) barrier[3];
                barrierSrcStage[b] = barrier[4];
                barrierSrcAccess[b] = barrier[5];
                barrierDstStage[b] = barrier[6];
                barrierDstAccess[b] = barrier[7];
                baseMip[b] = (int) barrier[9];
                mipCount[b] = (int) barrier[10];
                baseLayer[b] = (int) barrier[11];
                layerCount[b] = (int) barrier[12];
            }
            return new BarrierList(barrierStart, barrierTarget, barrierKind, barrierAspect, barrierOldLayout,
                barrierNewLayout, barrierSrcStage, barrierSrcAccess, barrierDstStage, barrierDstAccess, baseMip,
                mipCount, baseLayer, layerCount);
        }
    }
}
