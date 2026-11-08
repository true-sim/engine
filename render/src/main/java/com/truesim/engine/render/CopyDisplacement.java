package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.layout.DisplacePush;
import com.truesim.engine.render.layout.DisplacedCopyLayout;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link Deformation}'s displacement into copies ({@link Displacement#COMPUTE}; deformation/displace.slang): which
 * copies are displaced and for which instance, each source's vertex-to-submesh table (built once on the GPU), and the
 * frame's passes over every displaced copy at once: their sources copied in where not skinned, each displacing type's
 * displace() in place, their meshlets' spheres. A few dispatches a frame however many copies; the tables of copies and
 * chunks are written into plain memory and handed to the frame's copy in one piece. Allocates nothing per frame once
 * the copies stand.
 */
@AllowAllocation("copies come and go rarely")
final class CopyDisplacement {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 =
        ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int RECORD = DisplacedCopyLayout.BYTES;
    private static final int CHUNK = 64;
    private static final int ROW = 65535;
    private static final int NONE = -1;

    private final VulkanDevice device;
    private final GeometryPool pool;
    private final FrameSlots slots;
    private final Retiring retired;
    private final Arena arena = Arena.ofShared();
    // By mesh number: whether it is a displaced copy, and the instance it is displaced for
    private boolean[] displaced = new boolean[64];
    private int[] instanceOf = filled(64);
    // The displaced copies, densely
    private int[] copies = new int[16];
    private int count;
    private boolean changed = true; // the copies or their instances changed: chunks laid out again
    // Each source's vertex submeshes: its first word in owners, how many copies use it, whether built on the GPU
    private GpuBuffer owners;
    private final RangeAllocator ownerRanges = new RangeAllocator(1 << 16);
    private long[] ownerStart = new long[64];
    private int[] ownerUsers = new int[64];
    private boolean[] ownerBuilt = new boolean[64];
    // The frame's tables: a record per copy; the chunks: owners (sources to build), rest, displace, spheres
    private MemorySegment records;
    private MemorySegment chunks;
    private FrameBuffer frameRecords;
    private FrameBuffer frameChunks;
    private int ownerChunks;
    private int restChunks;
    private int vertexChunks;
    private int sphereChunks;
    private boolean chunksWritten;
    private final float[] box = new float[6];
    // The passes, made when first run; each displacing type's by its name
    private Pipeline ownersPass;
    private Pipeline restPass;
    private Pipeline spheresPass;
    private final Map<String, Pipeline> typePasses = new HashMap<>();
    private final DisplacePush push = new DisplacePush();
    private Materials typesOf;
    private int typesSeen = -1;
    private MaterialType[] types = new MaterialType[0];

    CopyDisplacement(VulkanDevice device, GeometryPool pool, FrameSlots slots) {
        this.device = device;
        this.pool = pool;
        this.slots = slots;
        this.retired = new Retiring(slots);
        owners = GpuBuffer.deviceLocal(device, 4L * ownerRanges.capacity(),
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        records = arena.allocate((long) RECORD * 16, 16);
        chunks = arena.allocate(8L * 64, 16);
    }

    private static int[] filled(int length) {
        int[] array = new int[length];
        Arrays.fill(array, NONE);
        return array;
    }

    /** Copy {@code copy} is displaced from the next frame on (its source's vertex submeshes built then). */
    void add(int copy) {
        if (copy >= displaced.length) {
            int length = Math.max(copy + 1, 2 * displaced.length);
            displaced = Arrays.copyOf(displaced, length);
            int old = instanceOf.length;
            instanceOf = Arrays.copyOf(instanceOf, length);
            Arrays.fill(instanceOf, old, length, NONE);
        }
        if (displaced[copy]) {
            return;
        }
        displaced[copy] = true;
        instanceOf[copy] = NONE;
        if (count == copies.length) {
            copies = Arrays.copyOf(copies, 2 * count);
        }
        copies[count++] = copy;
        holdOwners(pool.deformedFrom(copy));
        changed = true;
    }

    /** Lets go of copy {@code copy}: the pool removed it. */
    void forget(int copy) {
        if (!displaces(copy)) {
            return;
        }
        displaced[copy] = false;
        instanceOf[copy] = NONE;
        for (int i = 0; i < count; i++) {
            if (copies[i] == copy) {
                copies[i] = copies[--count];
                break;
            }
        }
        releaseOwners(pool.deformedFrom(copy));
        changed = true;
    }

    /** Whether pool mesh {@code mesh} is a displaced copy. */
    boolean displaces(int mesh) {
        return mesh >= 0 && mesh < displaced.length && displaced[mesh];
    }

    /** Displaced copy {@code copy} is displaced for instance {@code instance} (the one drawing it). */
    void bind(int copy, int instance) {
        if (instanceOf[copy] != instance) {
            instanceOf[copy] = instance; // read before written: instances are written on many threads
            synchronized (this) {
                changed = true;
            }
        }
    }

    /** How many copies it displaces. */
    int size() {
        return count;
    }

    private void holdOwners(int source) {
        if (source >= ownerUsers.length) {
            int length = Math.max(source + 1, 2 * ownerUsers.length);
            ownerStart = Arrays.copyOf(ownerStart, length);
            ownerUsers = Arrays.copyOf(ownerUsers, length);
            ownerBuilt = Arrays.copyOf(ownerBuilt, length);
        }
        if (ownerUsers[source]++ > 0) {
            return;
        }
        int vertices = pool.vertexCountOf(source);
        long start = ownerRanges.allocate(vertices, 1);
        if (start < 0) {
            long capacity = Math.max(2 * ownerRanges.capacity(), ownerRanges.end() + vertices);
            retired.add(owners); // frames in flight read it; every source's table is built again in the new one
            owners = GpuBuffer.deviceLocal(device, 4L * capacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            ownerRanges.grow(capacity);
            Arrays.fill(ownerBuilt, false);
            start = ownerRanges.allocate(vertices, 1);
        }
        ownerStart[source] = start;
        ownerBuilt[source] = false;
    }

    private void releaseOwners(int source) {
        if (--ownerUsers[source] > 0) {
            return;
        }
        // Frames in flight may read its range: released when the next copy needing room finds it (as the frames'
        // slots open, the tables they read are rebuilt in any range taken again before they were built)
        ownerRanges.release(ownerStart[source], pool.vertexCountOf(source));
        ownerBuilt[source] = false;
    }

    /**
     * Records the frame's displacement after the copies were skinned: {@code skinned} says which were posed in place
     * this frame, {@code previous} where each keeps the frame before's vertices (-1: none this frame), the box each is
     * quantized in and its detail spheres' growth ({@code bounds}, 7 floats a copy). Barriers before and after are
     * the caller's (the skinning's writes visible to compute; everything after reading the copies).
     */
    void record(PassContext ctx, SceneData.Buffers buffers, GpuScene scene, boolean[] skinned, long[] previous,
        boolean keeping, float[] bounds, long previousAddress) {
        retired.release();
        if (count == 0) {
            return;
        }
        Materials materials = scene.materials();
        boolean layout = changed || !chunksWritten || anyOwnersToBuild();
        if (layout) {
            layOutChunks();
        }
        ensureRecords();
        for (int i = 0; i < count; i++) {
            int copy = copies[i];
            long at = (long) RECORD * i;
            int b = 7 * copy;
            for (int axis = 0; axis < 3; axis++) {
                records.set(F32, at + DisplacedCopyLayout.BOX_MIN + 4L * axis, bounds[b + axis]);
                records.set(F32, at + DisplacedCopyLayout.BOX_EXTENT + 4L * axis, bounds[b + 3 + axis]);
            }
            int source = pool.deformedFrom(copy);
            records.set(U32, at + DisplacedCopyLayout.TARGET, copy);
            records.set(U32, at + DisplacedCopyLayout.SOURCE, source);
            records.set(U32, at + DisplacedCopyLayout.INSTANCE, Math.max(instanceOf[copy], 0));
            records.set(U32, at + DisplacedCopyLayout.SKINNED, skinned[copy] ? 1 : 0);
            records.set(U32, at + DisplacedCopyLayout.OWNERS, (int) ownerStart[source]);
            records.set(U32, at + DisplacedCopyLayout.PREVIOUS,
                keeping && !skinned[copy] && previous[copy] >= 0 ? (int) previous[copy] : NONE);
            records.set(F32, at + DisplacedCopyLayout.GROWTH, bounds[b + 6]);
            records.set(U32, at + DisplacedCopyLayout.VERTICES, pool.vertexCountOf(copy));
            records.set(U32, at + DisplacedCopyLayout.MESHLETS, pool.meshletCount(copy));
        }
        frameRecords.put(0, records, 0, (long) RECORD * count);
        if (layout) {
            frameChunks.put(0, chunks, 0, 8L * (ownerChunks + restChunks + vertexChunks + sphereChunks));
            chunksWritten = true;
        }
        makePasses();
        var commands = ctx.unsafe().commands();
        push.meshes(pool.meshTable().deviceAddress()).positions(ctx.address(buffers.positions()))
            .attributes(ctx.address(buffers.attributes())).meshlets(ctx.address(buffers.meshlets()))
            .meshletVertices(ctx.address(buffers.meshletVertices())).clusterLod(ctx.address(buffers.clusterLod()))
            .instances(ctx.address(buffers.instances())).materials(materials.deviceAddress())
            .hooks(scene.geometryHooks().address()).owners(owners.deviceAddress()).previous(previousAddress)
            .copies(frameRecords.deviceAddress()).type(0);
        long chunkBase = frameChunks.deviceAddress();
        if (ownerChunks > 0) {
            // Sources new to displacement: their vertices' submeshes, the lowest of the meshlets using each (after
            // whatever frames before read of the ranges)
            Barriers.memory(commands, org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_MEMORY_READ_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT);
            for (int i = 0; i < count; i++) {
                int source = pool.deformedFrom(copies[i]);
                if (!ownerBuilt[source]) {
                    Commands.fillBuffer(commands, owners.handle(), 4L * ownerStart[source],
                        4L * pool.vertexCountOf(source), -1);
                }
            }
            Barriers.memory(commands, VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                    | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            dispatch(ctx, ownersPass, chunkBase, ownerChunks);
            for (int i = 0; i < count; i++) {
                ownerBuilt[pool.deformedFrom(copies[i])] = true;
            }
            computeBarrier(commands);
        }
        long rest = chunkBase + 8L * ownerChunks;
        dispatch(ctx, restPass, rest, restChunks);
        computeBarrier(commands);
        long vertices = rest + 8L * restChunks;
        MaterialType[] types = displacingTypes(materials);
        for (MaterialType type : types) {
            push.type(materials.typeIndex(type));
            dispatch(ctx, typePass(type), vertices, vertexChunks);
        }
        computeBarrier(commands);
        dispatch(ctx, spheresPass, vertices + 8L * vertexChunks, sphereChunks);
        if (layout && ownerChunks > 0) {
            changed = true; // the owners' chunks only once: laid out again without them next frame
        }
    }

    /** {@code materials}' displacing types, as of its types now (made again only as types are registered). */
    private MaterialType[] displacingTypes(Materials materials) {
        if (materials != typesOf || materials.geometryTypeCount() != typesSeen) {
            typesOf = materials;
            typesSeen = materials.geometryTypeCount();
            types = materials.displacingTypes().toArray(new MaterialType[0]);
        }
        return types;
    }

    private boolean anyOwnersToBuild() {
        for (int i = 0; i < count; i++) {
            if (!ownerBuilt[pool.deformedFrom(copies[i])]) {
                return true;
            }
        }
        return false;
    }

    private static void computeBarrier(org.lwjgl.vulkan.VkCommandBuffer commands) {
        Barriers.memory(commands, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    /** {@code pass} over {@code chunks} chunks from {@code at}: rows of 65,535 workgroups. */
    private void dispatch(PassContext ctx, Pipeline pass, long at, int chunks) {
        if (chunks == 0) {
            return;
        }
        push.chunks(at).chunkCount(chunks);
        ctx.dispatch(pass, push, Math.min(chunks, ROW), (chunks + ROW - 1) / ROW, 1);
    }

    /** The chunk lists: owners of sources not built (a copy of each), copies not skinned, every copy, its meshlets. */
    private void layOutChunks() {
        synchronized (this) {
            changed = false;
        }
        int total = 0;
        for (int pass = 0; pass < 4; pass++) {
            int n = 0;
            boolean[] seen = pass == 0 ? new boolean[ownerBuilt.length] : null;
            for (int i = 0; i < count; i++) {
                int copy = copies[i];
                if (instanceOf[copy] < 0) {
                    continue; // drawn by no instance yet: nothing to displace it by
                }
                int source = pool.deformedFrom(copy);
                int items;
                if (pass == 0) {
                    if (ownerBuilt[source] || seen[source]) {
                        continue;
                    }
                    seen[source] = true;
                    items = pool.meshletCount(source);
                } else if (pass == 3) {
                    items = pool.meshletCount(copy);
                } else {
                    items = pool.vertexCountOf(copy); // pass 1 lists every copy: restMain skips the skinned ones
                }
                for (int first = 0; first < items; first += CHUNK) {
                    ensureChunks(total + n + 1);
                    long at = 8L * (total + n);
                    chunks.set(U32, at, i);
                    chunks.set(U32, at + 4, first);
                    n++;
                }
            }
            switch (pass) {
                case 0 -> ownerChunks = n;
                case 1 -> restChunks = n;
                case 2 -> vertexChunks = n;
                default -> sphereChunks = n;
            }
            total += n;
        }
        if (frameChunks == null || frameChunks.size() < 8L * total) {
            if (frameChunks != null) {
                retired.add(frameChunks);
            }
            frameChunks = FrameBuffer.create(device, Math.max(64, 16L * total),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        }
    }

    private void ensureChunks(int n) {
        if (8L * n > chunks.byteSize()) {
            MemorySegment grown = arena.allocate(Math.max(2 * chunks.byteSize(), 8L * n), 16);
            MemorySegment.copy(chunks, 0, grown, 0, chunks.byteSize());
            chunks = grown;
        }
    }

    private void ensureRecords() {
        long bytes = (long) RECORD * Math.max(count, 1);
        if (records.byteSize() < bytes) {
            records = arena.allocate(Math.max(2 * records.byteSize(), bytes), 16);
        }
        if (frameRecords == null || frameRecords.size() < bytes) {
            if (frameRecords != null) {
                retired.add(frameRecords);
            }
            frameRecords = FrameBuffer.create(device, Math.max(records.byteSize(), bytes),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        }
    }

    private void makePasses() {
        if (ownersPass == null) {
            try (ShaderModule shader = ShaderModule.load(device, "shaders/deformation/displaced.spv")) {
                ownersPass = Pipeline.compute(device, shader, "ownersMain", DisplacePush.BYTES);
                restPass = Pipeline.compute(device, shader, "restMain", DisplacePush.BYTES);
                spheresPass = Pipeline.compute(device, shader, "spheresMain", DisplacePush.BYTES);
            }
        }
    }

    private Pipeline typePass(MaterialType type) {
        Pipeline pass = typePasses.get(type.name());
        if (pass == null) {
            try (ShaderModule shader = ShaderModule.load(device, type.displaceComputeShader())) {
                pass = Pipeline.compute(device, shader, type.name() + "DisplaceMain", DisplacePush.BYTES);
            }
            typePasses.put(type.name(), pass);
        }
        return pass;
    }

    /** The source box of copy {@code copy} grown by {@code margin} on every side, into {@code bounds} at 7 * copy. */
    synchronized void restBounds(int copy, float margin, float[] bounds) {
        pool.box(pool.deformedFrom(copy), box);
        int b = 7 * copy;
        for (int axis = 0; axis < 3; axis++) {
            bounds[b + axis] = box[axis] - margin;
            bounds[b + 3 + axis] = box[3 + axis] + 2 * margin;
        }
        bounds[b + 6] = margin;
    }

    void close() {
        for (Pipeline pass : typePasses.values()) {
            pass.close();
        }
        if (ownersPass != null) {
            ownersPass.close();
            restPass.close();
            spheresPass.close();
        }
        if (frameRecords != null) {
            frameRecords.close();
        }
        if (frameChunks != null) {
            frameChunks.close();
        }
        owners.close();
        retired.close();
        arena.close();
    }
}
