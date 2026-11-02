package com.truesim.engine.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.layout.MaterialLayout;
import com.truesim.engine.render.materials.MetallicRoughnessMaterial;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The material table shaders read, by id, as engine-render's {@code materials.slang} lays it out: a 32-byte slot per
 * {@link Material} (how it rasterizes, where its blocks lie), then the blocks, its type's parameters and a section per
 * feature it shows. Material 0 is {@link Material#DEFAULT}, so meshes draw before anything is set up. Instances name a
 * first material ({@link GpuScene#set(int, int, int, float, float, float, float, float, float, float, float, float,
 * float)}); each submesh of the mesh takes the next.
 *
 * <p>The table also names the {@link MaterialType material types} its materials may have: metallic-roughness as type
 * 0, and those {@link #register registered}.
 *
 * <p>Materials are read when the frame's commands run. Given a frame graph's {@link FrameSlots}, the table keeps a copy
 * per frame in flight; otherwise change a material only once the frames using it have finished.
 *
 * <p>The table grows as materials come: the capacity it is made with is where it starts. Without ids in a row or room
 * for blocks, a table twice the size (or more) takes its place, holding what the old one holds (values changed in place
 * too); frames in flight keep reading the old one until they finish.
 */
@AllowAllocation("created rarely")
public final class Materials implements AutoCloseable {

    /** A material's slot. */
    static final int SLOT_BYTES = MaterialLayout.BYTES;
    /** Material types a table may name: their ids fill a header's 8 bits. */
    public static final int MAX_TYPES = 256;
    /** Room for blocks per material by default: metallic-roughness and a clear coat take 96. */
    public static final int BLOCK_BYTES_PER_MATERIAL = 128;
    /** Blocks start at multiples of this. */
    private static final int ALIGNMENT = 16;
    /**
     * Shading bins a table may number: combinations of type and features its materials show, each shaded by a variant of
     * its own (as resolve.slang's MAX_BINS).
     */
    public static final int MAX_BINS = 2048;

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    private final VulkanDevice device;
    private FrameBuffer table;
    private final Retiring oldTables; // replaced by bigger ones, until no frame in flight reads them
    private int capacity;
    private long heapStart;
    /** Ids in contiguous blocks: an add takes one, {@link #addAll} as many as materials. */
    private final RangeAllocator ids;
    /** The blocks, in units of {@link #ALIGNMENT} bytes. */
    private final RangeAllocator heap;
    /** Per id: the length of the block of ids starting there (0: not a block's start), and whether it is in use. */
    private int[] blockLength;
    private boolean[] used;
    /** Per id: its material, and its blocks (units of the heap; -1: none). */
    private Material[] materials;
    private int[][] named; // per material id: the textures it names, as counted (-1: none; null: none at all)
    private final int[] textureUsers = new int[com.truesim.engine.vulkan.Bindless.SAMPLED_SLOTS]; // per texture slot
    private final Gather gather = new Gather();
    private long version; // counts materials written and removed
    // Per material id with buffer fields: where each lies in its block, its buffer, the address last written there.
    private long[][] bufferAt;
    private com.truesim.engine.vulkan.DeviceBuffer[][] buffersOf;
    private long[][] bufferWritten;
    private final java.util.BitSet withBuffers = new java.util.BitSet();
    private final BufferGather bufferGather = new BufferGather();
    // The frame graph's buffers materials name, by how many fields name each: imported by the passes reading scene
    // data (SceneData), so they are ordered after the passes writing them. A new set rebuilds the graph.
    private final Map<com.truesim.engine.graph.ExternalBuffer, int[]> graphBufferUsers = new java.util.IdentityHashMap<>();
    private List<com.truesim.engine.graph.ExternalBuffer> graphBuffers = List.of();
    private long graphBuffersVersion;
    private long[] heapUnit;
    private int[] heapUnits;
    private int live;
    // Removed ids waiting for the frames that may read them: their first ids and when they are free.
    private int[] retiring;
    private long[] retireAt;
    private int retiringCount;
    private final List<MaterialType> types = new ArrayList<>();
    // The shading bins: per bin its type id and features, in the order they first appeared; never dropped.
    private int[] binTypes = new int[16];
    private int[] binFeatures = new int[16];
    private int bins;
    private final Map<Long, Integer> binOf = new HashMap<>();
    private int blended; // how many materials are drawn blended: the transparency pass runs while there are
    private int transmissive; // how many are transmissive: transparency copies the scene behind while there are
    /** The type of each parameters' record, where one type takes it. */
    private final Map<Class<?>, MaterialType> byRecord = new HashMap<>();
    /** Per parameters record: where in its block the mask alpha and mask texture lie (-1: none), as slots copy them. */
    private final Map<Class<?>, int[]> maskOffsets = new HashMap<>();
    private final Set<Class<?>> ambiguous = new HashSet<>();
    private final Arena arena = Arena.ofShared();
    private MemorySegment scratch;

    /**
     * A table starting with room for {@code capacity} materials, the default one included, in one copy: for frames the
     * caller waits for before changing materials.
     */
    public Materials(VulkanDevice device, int capacity) {
        this(device, capacity, FrameSlots.single());
    }

    /**
     * A table starting with room for {@code capacity} materials, the default one included, a copy per frame of
     * {@code slots} (a frame graph's), so materials may change while earlier frames are drawn. Room for
     * {@link #BLOCK_BYTES_PER_MATERIAL} bytes of blocks per material to start with.
     */
    public Materials(VulkanDevice device, int capacity, FrameSlots slots) {
        this(device, capacity, (long) BLOCK_BYTES_PER_MATERIAL * capacity, slots);
    }

    /** Like {@link #Materials(VulkanDevice, int, FrameSlots)}, with {@code blockBytes} for all the materials' blocks. */
    public Materials(VulkanDevice device, int capacity, long blockBytes, FrameSlots slots) {
        if (capacity < 1) {
            throw new IllegalArgumentException("At least the default material: " + capacity);
        }
        if (blockBytes < ALIGNMENT) {
            throw new IllegalArgumentException("Room for blocks: " + blockBytes);
        }
        this.device = java.util.Objects.requireNonNull(device, "device");
        this.oldTables = new Retiring(slots);
        this.capacity = capacity;
        ids = new RangeAllocator(capacity);
        heapStart = (long) SLOT_BYTES * capacity;
        long units = blockBytes / ALIGNMENT;
        heap = new RangeAllocator(units);
        blockLength = new int[capacity];
        used = new boolean[capacity];
        materials = new Material[capacity];
        named = new int[capacity][];
        bufferAt = new long[capacity][];
        buffersOf = new com.truesim.engine.vulkan.DeviceBuffer[capacity][];
        bufferWritten = new long[capacity][];
        heapUnit = new long[capacity];
        heapUnits = new int[capacity];
        Arrays.fill(heapUnit, -1);
        retiring = new int[capacity];
        retireAt = new long[capacity];
        scratch = arena.allocate(1024, ALIGNMENT);
        table = FrameBuffer.create(device, heapStart + units * ALIGNMENT, USAGE, slots);
        register(MetallicRoughnessMaterial.TYPE);
        add(Material.DEFAULT);
    }

    // ---------------------------------------------------------------------------------------------- types

    /**
     * Registers material type {@code type}, identified by its {@code MaterialType} (the generated {@code TYPE}
     * constant) and, in data, by its name. The table numbers types internally in registration order, so a number is
     * never an identity: nothing persists or keys on it. Renderers shading by type take the types when their graph
     * builds; a type registered later rebuilds the graphs shading this table by type before their next frame.
     */
    public void register(MaterialType type) {
        if (type(type.name()) != null) {
            throw new IllegalArgumentException("Material type " + type.name() + " is registered already");
        }
        if (types.size() == MAX_TYPES) {
            throw new IllegalStateException("A table names at most " + MAX_TYPES + " material types");
        }
        types.add(type);
        if (byRecord.putIfAbsent(type.parameters(), type) != null) {
            ambiguous.add(type.parameters());
        }
        maskOffsets.computeIfAbsent(type.parameters(), record -> findMasks(type));
    }

    /**
     * Where the fields a record's {@code maskAlpha()} and {@code maskTexture()} read lie in its block: found by making
     * the record from values with one field at a time set to a marker, and seeing which comes back as the mask. So
     * {@link #update} keeps the slot's copies (what alpha masking reads) with the block.
     */
    @AllowAllocation("registering a type, once")
    private static int[] findMasks(MaterialType type) {
        int[] found = {-1, -1};
        for (java.lang.reflect.Field constant : type.parameters().getFields()) {
            if (constant.getType() != MaterialField.class
                || !java.lang.reflect.Modifier.isStatic(constant.getModifiers())) {
                continue;
            }
            MaterialField field;
            try {
                field = (MaterialField) constant.get(null);
            } catch (IllegalAccessException e) {
                continue;
            }
            for (int component = 0; component < Math.max(1, field.count()); component++) {
                MaterialParameters probed = type.fromValues().apply(new MaskProbe(field.path(), component, ""));
                if (field.kind() != MaterialField.Kind.I32 && probed.maskAlpha() == MaskProbe.ALPHA) {
                    found[0] = field.offset() + 4 * component;
                }
                if (field.kind() == MaterialField.Kind.I32 && probed.maskTexture() == MaskProbe.TEXTURE) {
                    found[1] = field.offset();
                }
            }
        }
        return found;
    }

    /** Values that are the fallbacks but for one field's component, a marker: to see what a record's mask reads. */
    private record MaskProbe(String path, int component, String prefix) implements MaterialValues {
        static final float ALPHA = 0.123456f;
        static final int TEXTURE = 987_654;

        private boolean probed(String name) {
            return path.equals(prefix + name);
        }

        /** Component {@code i} of a probed vector: the marker in the component probed, else 0. */
        private float c(int i) {
            return component == i ? ALPHA : 0;
        }

        @Override
        public float f32(String name, float fallback) {
            return probed(name) ? ALPHA : fallback;
        }

        @Override
        public int i32(String name, int fallback) {
            return probed(name) ? TEXTURE : fallback;
        }

        @Override
        public com.truesim.common.math.Vec2 vec2(String name, com.truesim.common.math.Vec2 fallback) {
            return probed(name) ? new com.truesim.common.math.Vec2(c(0), c(1)) : fallback;
        }

        @Override
        public com.truesim.common.math.Vec3 vec3(String name, com.truesim.common.math.Vec3 fallback) {
            return probed(name) ? new com.truesim.common.math.Vec3(c(0), c(1), c(2)) : fallback;
        }

        @Override
        public com.truesim.common.math.Vec4 vec4(String name, com.truesim.common.math.Vec4 fallback) {
            return probed(name) ? new com.truesim.common.math.Vec4(c(0), c(1), c(2), c(3)) : fallback;
        }

        @Override
        public com.truesim.common.math.color.Color color(String name, com.truesim.common.math.color.Color fallback) {
            return probed(name) ? new com.truesim.common.math.color.Color(c(0), c(1), c(2), c(3)) : fallback;
        }

        @Override
        public int texture(String name, boolean srgb, int fallback) {
            return probed(name) ? TEXTURE : fallback;
        }

        @Override
        public MaterialValues nested(String name) {
            return new MaskProbe(path, component, prefix + name + ".");
        }
    }

    /**
     * After a field of material {@code id}'s parameters was written in place at block offset {@code at} ({@code bytes}
     * long): the slot's copy of the mask alpha or texture, if the field holds it, so alpha masking follows.
     */
    private void followMasks(int id, MaterialField field, long at, int bytes) {
        Material material = material(id);
        if (field.owner() != material.parameters().getClass()) {
            return; // a feature's: features hold no mask
        }
        int[] masks = maskOffsets.get(field.owner());
        if (masks == null) {
            return;
        }
        long block = heapStart + heapUnit[id] * ALIGNMENT;
        long slot = (long) SLOT_BYTES * id;
        if (masks[0] >= 0 && block + masks[0] >= at && block + masks[0] < at + bytes) {
            table.putFloat(slot + MaterialLayout.MASK_ALPHA, table.getFloat(block + masks[0]));
        }
        if (masks[1] >= 0 && block + masks[1] >= at && block + masks[1] < at + bytes) {
            table.putInt(slot + MaterialLayout.MASK_TEXTURE, table.getInt(block + masks[1]));
        }
    }

    /** How many material types are registered. Allocates nothing. */
    public int typeCount() {
        return types.size();
    }

    /** The material types, in registration order: metallic-roughness first. */
    public List<MaterialType> types() {
        return List.copyOf(types);
    }

    /** The registered type called {@code name}, or null. */
    public MaterialType type(String name) {
        for (MaterialType type : types) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /** {@code material}'s type id: its own type's, or the one type registered for its parameters' record. */
    private int typeId(Material material) {
        MaterialType type = material.type();
        if (type == null) {
            Class<?> record = material.parameters().getClass();
            if (ambiguous.contains(record)) {
                throw new IllegalArgumentException("Several material types take " + record.getSimpleName()
                    + ": name one, new Material(type, parameters)");
            }
            type = byRecord.get(record);
            if (type == null) {
                throw new IllegalArgumentException("No material type takes " + record.getSimpleName()
                    + ": register its type first");
            }
        }
        int id = types.indexOf(type);
        if (id < 0) {
            throw new IllegalArgumentException("Material type " + type.name() + " is not registered");
        }
        int shown = material.featureMask() & ~Material.EVERY_TYPES_FEATURES;
        if ((shown & ~type.allowedFeatures()) != 0) {
            throw new IllegalArgumentException("Material type " + type.name() + " does not allow features "
                + (shown & ~type.allowedFeatures()));
        }
        return id;
    }

    // ---------------------------------------------------------------------------------------------- materials

    /**
     * Adds {@code material} and returns its id: the lowest free one, ids of removed materials once no frame in
     * flight reads them. The table grows if it is full.
     */
    public int add(Material material) {
        int id = take(1);
        write(id, material);
        return id;
    }

    /**
     * Adds {@code materials} under consecutive ids and returns the first, e.g. one per submesh of a mesh, which an
     * instance names by the first: submesh s takes the first id plus s. {@link #remove} of the first removes them all.
     */
    public int addAll(List<Material> materials) {
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("No materials to add");
        }
        int first = take(materials.size());
        for (int i = 0; i < materials.size(); i++) {
            write(first + i, materials.get(i));
        }
        return first;
    }

    /** The first of {@code length} free ids in a row, taken: the table grows if it has none. */
    private int take(int length) {
        releaseRetired();
        long first = ids.allocate(length, 1);
        if (first < 0) {
            grow(Math.max(2L * capacity, (long) capacity + length), heap.capacity());
            first = ids.allocate(length, 1); // the free end joins the new room
        }
        int id = (int) first;
        blockLength[id] = length;
        for (int i = id; i < id + length; i++) {
            used[i] = true;
        }
        live += length;
        return id;
    }

    /**
     * A table of {@code ids} ids and {@code units} units of blocks in this one's place, holding what it holds: the slots
     * as they are, the blocks too, moved up past the new slots (their offsets with them). The old table goes once no
     * frame in flight reads it.
     */
    @AllowAllocation("growing, rarely")
    private void grow(long ids, long units) {
        if (ids > Integer.MAX_VALUE / SLOT_BYTES) {
            throw new IllegalStateException("A material table holds at most " + Integer.MAX_VALUE / SLOT_BYTES
                + " materials");
        }
        int more = (int) ids;
        long start = (long) SLOT_BYTES * more;
        long shift = start - heapStart;
        FrameBuffer old = table;
        FrameBuffer grown = FrameBuffer.create(device, start + units * ALIGNMENT, USAGE, old.slots());
        grown.copyFrom(old, 0, 0, heapStart);
        grown.copyFrom(old, heapStart, start, old.size() - heapStart);
        for (int id = 0; id < capacity; id++) {
            if (!used[id]) {
                continue; // removed: frames recorded from now on read it no more
            }
            long slot = (long) SLOT_BYTES * id;
            long parameters = slot + MaterialLayout.PARAMETERS;
            grown.putInt(parameters, (int) (grown.getInt(parameters) + shift)); // its sections lie relative to it
        }
        oldTables.add(old);
        table = grown;
        if (more > capacity) {
            this.ids.grow(more);
            blockLength = Arrays.copyOf(blockLength, more);
            used = Arrays.copyOf(used, more);
            materials = Arrays.copyOf(materials, more);
            named = Arrays.copyOf(named, more);
            bufferAt = Arrays.copyOf(bufferAt, more);
            buffersOf = Arrays.copyOf(buffersOf, more);
            bufferWritten = Arrays.copyOf(bufferWritten, more);
            heapUnit = Arrays.copyOf(heapUnit, more);
            Arrays.fill(heapUnit, capacity, more, -1);
            heapUnits = Arrays.copyOf(heapUnits, more);
            retiring = Arrays.copyOf(retiring, more);
            retireAt = Arrays.copyOf(retireAt, more);
            capacity = more;
        }
        if (units > heap.capacity()) {
            heap.grow(units);
        }
        heapStart = start;
    }

    /** Replaces material {@code id} with {@code material}; any id of a block. */
    public void set(int id, Material material) {
        if (!contains(id)) {
            throw new IllegalArgumentException("No material " + id);
        }
        write(id, material);
    }

    /**
     * Sets field {@code field} of material {@code id}'s parameters, or of one of its features' (a generated record's
     * constant, e.g. {@code CarPaintParameters.FLAKE_DENSITY}), to {@code value}, in place: for a value changing every
     * frame. {@link #material} keeps the material as it was set. Allocates nothing.
     */
    public void update(int id, MaterialField field, float value) {
        if (field.kind() != MaterialField.Kind.F32) {
            throw new IllegalArgumentException(field + " holds no float");
        }
        if (!field.accepts(value)) {
            throw new IllegalArgumentException(field + " is out of its range: " + value);
        }
        long offset = fieldOffset(id, field);
        table.putFloat(offset, value);
        followMasks(id, field, offset, 4);
    }

    /** {@link #update(int, MaterialField, float)} for an integer field, or a texture slot. */
    public void update(int id, MaterialField field, int value) {
        if (field.kind() != MaterialField.Kind.I32) {
            throw new IllegalArgumentException(field + " holds no integer");
        }
        long offset = fieldOffset(id, field);
        if (field.texture()) {
            renamed(id, table.getInt(offset), value);
        }
        table.putInt(offset, value);
        followMasks(id, field, offset, 4);
    }

    /**
     * How many materials name texture {@code texture} ({@link Textures}' number, any sampler): those whose
     * {@code [Texture]} fields hold it, as added, set or updated. A texture is removed only at none
     * ({@link Textures#namedBy}). Allocates nothing.
     */
    public int textureUsers(int texture) {
        return texture < 0 ? 0 : textureUsers[texture & 0xffff];
    }

    /** Material {@code id} names the textures of {@code material} now, no longer those it named before. */
    private void name(int id, Material material) {
        unname(id);
        gather.count = 0;
        material.parameters().textures(gather);
        for (MaterialFeature feature : material.features()) {
            feature.textures(gather);
        }
        int[] textures = Arrays.copyOf(gather.textures, gather.count);
        for (int texture : textures) {
            count(texture, 1);
        }
        named[id] = textures;
    }

    /** Material {@code id} names no textures. */
    private void unname(int id) {
        int[] textures = named[id];
        if (textures != null) {
            for (int texture : textures) {
                count(texture, -1);
            }
            named[id] = null;
        }
    }

    /** One of material {@code id}'s texture fields went from {@code from} to {@code to}. Allocates nothing. */
    private void renamed(int id, int from, int to) {
        int[] textures = named[id];
        for (int i = 0; textures != null && i < textures.length; i++) {
            if (textures[i] == from) {
                textures[i] = to;
                count(from, -1);
                count(to, 1);
                return;
            }
        }
    }

    private void count(int texture, int by) {
        if (texture >= 0) {
            textureUsers[texture & 0xffff] += by;
        }
    }

    /** The texture fields' values of a material, gathered: reused, so naming allocates only the copy kept. */
    private static final class Gather implements java.util.function.IntConsumer {
        int[] textures = new int[8];
        int count;

        @Override
        public void accept(int texture) {
            if (count == textures.length) {
                textures = Arrays.copyOf(textures, 2 * count);
            }
            textures[count++] = texture;
        }
    }

    /**
     * {@link #update(int, MaterialField, float)} for a buffer field ({@code T*} in Slang): {@code buffer}'s address from
     * now on, followed as it changes; null for none. {@link #material} keeps the material as it was set.
     */
    @AllowAllocation("a graph buffer named anew, rarely")
    public void update(int id, MaterialField field, com.truesim.engine.vulkan.DeviceBuffer buffer) {
        if (field.kind() != MaterialField.Kind.BUFFER) {
            throw new IllegalArgumentException(field + " holds no buffer");
        }
        long offset = fieldOffset(id, field);
        long at = offset - (heapStart + heapUnit[id] * ALIGNMENT);
        long[] where = bufferAt[id];
        for (int i = 0; where != null && i < where.length; i++) {
            if (where[i] == at) {
                countGraphBuffer(buffersOf[id][i], -1);
                countGraphBuffer(buffer, 1);
                buffersOf[id][i] = buffer;
                bufferWritten[id][i] = addressOf(buffer);
                table.putLong(offset, bufferWritten[id][i]);
                return;
            }
        }
        throw new IllegalStateException("Material " + id + "'s " + field + " was not gathered"); // the record lied
    }

    /**
     * Writes the addresses of the buffers materials name where they changed since last written: a {@code FrameBuffer}'s
     * open slot's copy, a buffer grown or set anew. Called as each frame records, before shading (the scene's upload).
     * Allocates nothing.
     */
    void followBuffers() {
        for (int id = withBuffers.nextSetBit(0); id >= 0; id = withBuffers.nextSetBit(id + 1)) {
            long block = heapStart + heapUnit[id] * ALIGNMENT;
            com.truesim.engine.vulkan.DeviceBuffer[] buffers = buffersOf[id];
            for (int i = 0; i < buffers.length; i++) {
                long address = addressOf(buffers[i]);
                if (address != bufferWritten[id][i]) {
                    table.putLong(block + bufferAt[id][i], address);
                    bufferWritten[id][i] = address;
                }
            }
        }
    }

    /**
     * The frame graph's buffers ({@code ExternalBuffer}) materials name now: the passes reading scene data read them
     * too ({@link SceneData}), so the graph orders those passes after the ones writing them.
     */
    public List<com.truesim.engine.graph.ExternalBuffer> graphBuffers() {
        return graphBuffers;
    }

    /** Changes as the set of {@link #graphBuffers} does: techniques shading materials rebuild the graph for it. */
    public long graphBuffersVersion() {
        return graphBuffersVersion;
    }

    private static long addressOf(com.truesim.engine.vulkan.DeviceBuffer buffer) {
        return buffer == null ? 0 : buffer.deviceAddress();
    }

    /** Material {@code id}'s buffer fields are those of {@code material} now, its block's addresses written. */
    @AllowAllocation("adding or setting a material")
    private void nameBuffers(int id, Material material) {
        unnameBuffers(id);
        bufferGather.count = 0;
        material.parameters().buffers(0, bufferGather);
        long o = align(material.parameters().bytes());
        for (MaterialFeature feature : material.features()) {
            feature.buffers(o, bufferGather);
            o += align(feature.bytes());
        }
        int count = bufferGather.count;
        if (count == 0) {
            return;
        }
        bufferAt[id] = Arrays.copyOf(bufferGather.at, count);
        buffersOf[id] = Arrays.copyOf(bufferGather.buffers, count);
        bufferWritten[id] = new long[count];
        long block = heapStart + heapUnit[id] * ALIGNMENT;
        for (int i = 0; i < count; i++) {
            countGraphBuffer(buffersOf[id][i], 1);
            bufferWritten[id][i] = table.getLong(block + bufferAt[id][i]); // as the record wrote it
        }
        withBuffers.set(id);
    }

    /** Material {@code id} names no buffers. */
    private void unnameBuffers(int id) {
        com.truesim.engine.vulkan.DeviceBuffer[] buffers = buffersOf[id];
        if (buffers != null) {
            for (com.truesim.engine.vulkan.DeviceBuffer buffer : buffers) {
                countGraphBuffer(buffer, -1);
            }
        }
        bufferAt[id] = null;
        buffersOf[id] = null;
        bufferWritten[id] = null;
        withBuffers.clear(id);
    }

    /** One more ({@code by} 1) or one fewer (-1) field names {@code buffer}: the graph's set follows. */
    private void countGraphBuffer(com.truesim.engine.vulkan.DeviceBuffer buffer, int by) {
        if (!(buffer instanceof com.truesim.engine.graph.ExternalBuffer external)) {
            return;
        }
        int[] users = graphBufferUsers.get(external);
        if (users == null) {
            graphBufferUsers.put(external, users = new int[1]);
        }
        users[0] += by;
        if (users[0] == 0 || users[0] == by) { // gone, or new: another set
            if (users[0] == 0) {
                graphBufferUsers.remove(external);
            }
            graphBuffers = List.copyOf(graphBufferUsers.keySet());
            graphBuffersVersion++;
        }
    }

    /** A material's buffer fields, gathered: reused, so naming allocates only the copies kept. */
    private static final class BufferGather implements MaterialBuffers {
        long[] at = new long[4];
        com.truesim.engine.vulkan.DeviceBuffer[] buffers = new com.truesim.engine.vulkan.DeviceBuffer[4];
        int count;

        @Override
        public void accept(long offset, com.truesim.engine.vulkan.DeviceBuffer buffer) {
            if (count == at.length) {
                at = Arrays.copyOf(at, 2 * count);
                buffers = Arrays.copyOf(buffers, 2 * count);
            }
            at[count] = offset;
            buffers[count++] = buffer;
        }
    }

    /** {@link #update(int, MaterialField, float)} for a vector or color field: its first {@code count()} values. */
    public void update(int id, MaterialField field, float x, float y, float z, float w) {
        if (field.kind() != MaterialField.Kind.FLOATS) {
            throw new IllegalArgumentException(field + " holds no vector");
        }
        long offset = fieldOffset(id, field);
        table.putFloat(offset, x);
        table.putFloat(offset + 4, y);
        if (field.count() > 2) {
            table.putFloat(offset + 8, z);
        }
        if (field.count() > 3) {
            table.putFloat(offset + 12, w);
        }
        followMasks(id, field, offset, 4 * field.count());
    }

    /**
     * Component {@code component} of field {@code field} of material {@code id} as the table holds it now, updates in
     * place included ({@link #material} keeps the material as it was set): for tools and tests. Allocates nothing.
     */
    public float value(int id, MaterialField field, int component) {
        if (field.kind() == MaterialField.Kind.BUFFER || component < 0 || component >= field.count()) {
            throw new IllegalArgumentException(field + " has no float " + component);
        }
        long offset = fieldOffset(id, field) + 4L * component;
        return field.kind() == MaterialField.Kind.I32 ? table.getInt(offset) : table.getFloat(offset);
    }

    private long fieldOffset(int id, MaterialField field) {
        Material material = material(id);
        long offset = heapStart + heapUnit[id] * ALIGNMENT;
        if (field.owner() == material.parameters().getClass()) {
            return offset + field.offset();
        }
        offset += align(material.parameters().bytes());
        for (MaterialFeature feature : material.features()) {
            if (field.owner() == feature.getClass()) {
                return offset + field.offset();
            }
            offset += align(feature.bytes());
        }
        throw new IllegalArgumentException("Material " + id + " has no " + field);
    }

    /**
     * Removes material {@code id} (not the default, 0), or the block {@link #addAll} returned {@code id} for: instances
     * must no longer use them from the next frame on. The ids are reused once no frame in flight can read them.
     * Allocates nothing.
     */
    public void remove(int id) {
        if (id <= 0 || id >= capacity || blockLength[id] == 0 || !used[id]) {
            throw new IllegalArgumentException("No material " + id + " to remove, or not the first of its block");
        }
        int length = blockLength[id];
        for (int i = id; i < id + length; i++) {
            used[i] = false;
            // The frames in flight read their own copies of the table, which keep the blocks till their slot opens.
            freeBlocks(i);
        }
        live -= length;
        version++;
        retiring[retiringCount] = id;
        retireAt[retiringCount++] = table.slots().retireAt();
    }

    /** Whether {@code id} is a material of the table, added and not removed. */
    public boolean contains(int id) {
        return id >= 0 && id < capacity && used[id];
    }

    /** Material {@code id}, as it was added or set. */
    public Material material(int id) {
        if (!contains(id)) {
            throw new IllegalArgumentException("No material " + id);
        }
        return materials[id];
    }

    /**
     * The shading bin of type id {@code typeId} with features {@code features}: numbered as combinations first appear,
     * up to {@link #MAX_BINS}. A new one changes {@link #binsVersion}.
     */
    private int bin(int typeId, int features) {
        Integer known = binOf.get((long) typeId << 32 | features & 0xffffffffL);
        if (known != null) {
            return known;
        }
        if (bins == MAX_BINS) {
            throw new IllegalStateException("A table shades at most " + MAX_BINS + " combinations of type and features");
        }
        if (bins == binTypes.length) {
            binTypes = Arrays.copyOf(binTypes, 2 * bins);
            binFeatures = Arrays.copyOf(binFeatures, 2 * bins);
        }
        binTypes[bins] = typeId;
        binFeatures[bins] = features;
        binOf.put((long) typeId << 32 | features & 0xffffffffL, bins);
        return bins++;
    }

    /**
     * How many shading bins the table numbers: combinations of type and features its materials showed, in the order
     * they first appeared; bin 0 is metallic-roughness without features. The visibility buffer shades a bin with a
     * variant of its type's pass showing its features, so a new bin rebuilds the graph. Allocates nothing.
     */
    public int binCount() {
        return bins;
    }

    /**
     * Whether the table holds a blended material ({@link Material#alphaBlend}): the transparency pass runs only while
     * it does, so a scene without one pays nothing for it; a change rebuilds the graph. Allocates nothing.
     */
    public boolean hasBlended() {
        return blended > 0;
    }

    /**
     * Whether the table holds a transmissive material ({@link Material#FEATURE_TRANSMISSION}, drawn blended too):
     * transparency copies the opaque scene for it to show ({@link SceneBehind}) only while it does. Allocates nothing.
     */
    public boolean hasTransmissive() {
        return transmissive > 0;
    }

    /** Bin {@code bin}'s material type. */
    public MaterialType binType(int bin) {
        return types.get(binTypes[java.util.Objects.checkIndex(bin, bins)]);
    }

    /** Bin {@code bin}'s features ({@code Material.FEATURE_*} bits). */
    public int binFeatures(int bin) {
        return binFeatures[java.util.Objects.checkIndex(bin, bins)];
    }

    /** The features ({@code Material.FEATURE_*} bits) material {@code id} shows. Allocates nothing. */
    public int features(int id) {
        return material(id).featureMask();
    }

    /** Gives removed ids no frame in flight reads back, oldest first. */
    private void releaseRetired() {
        oldTables.release(); // tables outgrown, once no frame in flight reads them
        int kept = 0;
        for (int i = 0; i < retiringCount; i++) {
            int id = retiring[i];
            if (table.slots().isFinished(retireAt[i])) {
                ids.release(id, blockLength[id]);
                blockLength[id] = 0;
            } else {
                retiring[kept] = id;
                retireAt[kept++] = retireAt[i];
            }
        }
        retiringCount = kept;
    }

    private void freeBlocks(int id) {
        if (heapUnit[id] >= 0) {
            heap.release(heapUnit[id], heapUnits[id]);
            heapUnit[id] = -1;
            heapUnits[id] = 0;
        }
        count(materials[id], -1);
        materials[id] = null;
        unname(id);
        unnameBuffers(id);
    }

    /** Counts {@code material} (nullable) in or out ({@code by} 1 or -1) of the blended and transmissive ones. */
    private void count(Material material, int by) {
        if (material != null && material.drawnBlended()) {
            blended += by;
        }
        if (material != null && material.shows(Material.FEATURE_TRANSMISSION)) {
            transmissive += by;
        }
    }

    private static long align(long bytes) {
        return (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
    }

    /** Writes {@code material}'s blocks and slot under {@code id}; false (and nothing changed) without heap room. */
    private void write(int id, Material material) {
        int typeId = typeId(material);
        int bin = bin(typeId, material.featureMask());
        long bytes = align(material.parameters().bytes());
        for (MaterialFeature feature : material.features()) {
            bytes += align(feature.bytes());
        }
        long featureTable = bytes;
        bytes += align(4L * material.features().size());
        int units = (int) (bytes / ALIGNMENT);
        long unit = heapUnit[id];
        if (unit < 0 || heapUnits[id] != units) {
            long fresh = heap.allocate(units, 1);
            if (fresh < 0) {
                grow(capacity, Math.max(2 * heap.capacity(), heap.capacity() + units));
                fresh = heap.allocate(units, 1); // the free end joins the new room
            }
            if (unit >= 0) {
                heap.release(unit, heapUnits[id]);
            }
            unit = fresh;
            heapUnit[id] = unit;
            heapUnits[id] = units;
        }
        if (scratch.byteSize() < bytes) {
            scratch = arena.allocate(bytes, ALIGNMENT);
        }
        long block = heapStart + unit * ALIGNMENT;
        material.parameters().write(scratch, 0);
        long slot = (long) SLOT_BYTES * id;
        long o = align(material.parameters().bytes());
        long entry = featureTable;
        for (MaterialFeature feature : material.features()) { // in bit order, as the table lists them
            feature.write(scratch, o);
            scratch.set(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, entry, (int) o);
            entry += 4;
            o += align(feature.bytes());
        }
        table.put(block, scratch, 0, bytes);
        table.putInt(slot + MaterialLayout.HEADER, material.header(typeId));
        table.putInt(slot + MaterialLayout.PARAMETERS, (int) block);
        table.putInt(slot + MaterialLayout.MASK_TEXTURE, material.parameters().maskTexture());
        table.putFloat(slot + MaterialLayout.MASK_ALPHA, material.parameters().maskAlpha());
        table.putInt(slot + MaterialLayout.GEOMETRY, 0);
        table.putInt(slot + MaterialLayout.FEATURES, material.featureMask());
        table.putInt(slot + MaterialLayout.FEATURE_TABLE, (int) featureTable);
        table.putInt(slot + MaterialLayout.BIN, bin);
        count(materials[id], -1);
        count(material, 1);
        materials[id] = material;
        name(id, material);
        nameBuffers(id, material);
        version++;
    }

    /**
     * Changes as materials are added, set or removed (not as fields are updated in place): what was built from the
     * materials' kinds, as a top level's opaque and masked instances, is built again. Allocates nothing.
     */
    public long version() {
        return version;
    }

    /** How many materials the table holds, the default one included. */
    public int count() {
        return live;
    }

    /**
     * The table's device address, for shaders: material i's slot at {@code i * 32}, its blocks at the offsets the slot
     * gives, as materials.slang reads them.
     */
    public long deviceAddress() {
        return table.deviceAddress();
    }

    @Override
    public void close() {
        table.close();
        oldTables.close();
        arena.close();
    }
}
