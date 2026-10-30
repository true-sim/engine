package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.common.graph.Resolution;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What techniques and requests build with: images, buffers, passes, and the results of other techniques. Requiring a
 * result builds the technique that provides it, the first time, so providers' passes always come before their
 * consumers', and techniques nothing needs are never built. Passes run in the order they are added; their declared
 * accesses decide the barriers.
 *
 * <p>Results are single owner: a pass may write only images and buffers its own technique or request made (or external
 * images), so whoever requires a result sees it complete.
 */
@AllowAllocation("building the graph, when it changes")
public final class GraphBuilder {

    /** One image of the build. */
    static final class ImageInfo {
        final Image image;
        Object owner;
        final ExternalImage external;
        int usage;
        History history; // one of a history's images
        boolean previous; // its last frame's: read only

        ImageInfo(Image image, Object owner, ExternalImage external) {
            this.image = image;
            this.owner = owner;
            this.external = external;
        }
    }

    /** One transient buffer of the build. */
    static final class BufferInfo {
        final Buffer buffer;
        final ExternalBuffer external; // or null: a transient buffer
        Object owner;
        int usage;

        BufferInfo(Buffer buffer, Object owner) {
            this(buffer, owner, null);
        }

        BufferInfo(Buffer buffer, Object owner, ExternalBuffer external) {
            this.buffer = buffer;
            this.owner = owner;
            this.external = external;
        }
    }

    /**
     * An attachment of a pass's rendering scope: {@code target} (one mip level of one layer), loaded and stored as
     * said, cleared to {@code clear}; a depth attachment with {@code depth}; a multisampled color one resolved into
     * {@code resolve} (or null).
     */
    record Attachment(Subresource target, Load load, Store store, Clear clear, boolean depth, Subresource resolve) {
    }

    /**
     * One pass of the build: its accesses (image id and access, in pairs, with each access's mip and layer range: base
     * mip, mip count, base layer, layer count, four per access) and body.
     */
    static final class PassInfo {
        final String name;
        final String owner;
        final int[] images;
        final Access[] accesses;
        final int[] ranges;
        final boolean[] discards; // per image access: its contents are not needed (an attachment cleared or not loaded)
        final Attachment[] attachments; // the rendering scope the graph opens around the body; none: no scope
        final int[] buffers;
        final BufferAccess[] bufferAccesses;
        final boolean sideEffects;
        final boolean async;
        final PassBody body;

        PassInfo(String name, String owner, int[] images, Access[] accesses, int[] ranges, boolean[] discards,
            Attachment[] attachments, int[] buffers, BufferAccess[] bufferAccesses, boolean sideEffects, boolean async,
            PassBody body) {
            this.name = name;
            this.owner = owner;
            this.images = images;
            this.accesses = accesses;
            this.ranges = ranges;
            this.discards = discards;
            this.attachments = attachments;
            this.buffers = buffers;
            this.bufferAccesses = bufferAccesses;
            this.sideEffects = sideEffects;
            this.async = async;
            this.body = body;
        }
    }

    final List<ImageInfo> images = new ArrayList<>();
    final List<BufferInfo> buffers = new ArrayList<>();
    final List<History> histories = new ArrayList<>();
    final List<PassInfo> passes = new ArrayList<>();
    private final Resolution<Key<?>, Technique> resolution;
    private final Map<ExternalImage, Image> externals = new IdentityHashMap<>();
    private final Map<ExternalBuffer, Buffer> externalBuffers = new IdentityHashMap<>();

    private final Map<Class<?>, Implementation<?, ?>> implementations;

    private final FrameGraph graph;

    GraphBuilder(FrameGraph graph, List<Technique> techniques, Map<Class<?>, Implementation<?, ?>> implementations) {
        this.graph = graph;
        this.implementations = implementations;
        this.resolution = new Resolution<>(techniques, new Resolution.Producers<>() {
            @Override
            public List<Key<?>> provides(Technique technique) {
                return technique.provides();
            }

            @Override
            public List<Key<?>> modifies(Technique technique) {
                return technique.modifies();
            }

            @Override
            public void build(Technique technique) {
                technique.build(GraphBuilder.this);
            }

            @Override
            public String name(Technique technique) {
                return GraphBuilder.name(technique);
            }
        }, Comparator.comparingInt(positions(techniques)::get), "technique");
    }

    /**
     * Each technique's place in the order modifiers run in: by {@link Order#anchor() anchor}, then after and before
     * others by id (ids of techniques not in the graph are ignored), and otherwise in the order added.
     *
     * @throws IllegalStateException if two techniques have one id, or their orders contradict each other
     */
    static Map<Technique, Integer> positions(List<Technique> techniques) {
        int n = techniques.size();
        Map<Id<?>, Integer> byId = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Id<?> id = techniques.get(i).id();
            if (id != null && byId.putIfAbsent(id, i) != null) {
                throw new IllegalStateException("Two techniques have the id " + id + ": " + name(techniques.get(i))
                    + " and " + name(techniques.get(byId.get(id))));
            }
        }
        boolean[][] before = new boolean[n][n]; // before[a][b]: a runs before b
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                before[a][b] = techniques.get(a).order().anchor().rank() < techniques.get(b).order().anchor().rank();
            }
        }
        for (int a = 0; a < n; a++) { // then the explicit orders, which the anchors' must not overwrite
            Order order = techniques.get(a).order();
            for (Id<?> id : order.after()) {
                Integer other = byId.get(id);
                if (other != null) {
                    before[other][a] = true;
                }
            }
            for (Id<?> id : order.before()) {
                Integer other = byId.get(id);
                if (other != null) {
                    before[a][other] = true;
                }
            }
        }
        int[] waiting = new int[n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                waiting[b] += before[a][b] ? 1 : 0;
            }
        }
        Map<Technique, Integer> positions = new IdentityHashMap<>();
        boolean[] placed = new boolean[n];
        for (int position = 0; position < n; position++) {
            int next = 0;
            while (next < n && (placed[next] || waiting[next] > 0)) {
                next++; // the first added of those nothing has to run before any more
            }
            if (next == n) {
                List<String> left = new ArrayList<>();
                for (int a = 0; a < n; a++) {
                    if (!placed[a]) {
                        left.add(name(techniques.get(a)));
                    }
                }
                throw new IllegalStateException("The orders of techniques contradict each other: " + left);
            }
            placed[next] = true;
            positions.put(techniques.get(next), position);
            for (int b = 0; b < n; b++) {
                waiting[b] -= before[next][b] ? 1 : 0;
            }
        }
        return positions;
    }

    /** What is being built: a technique, a request, or null (the graph itself). What it makes belongs to it. */
    private Object scope() {
        return resolution.scope();
    }

    // ---------------------------------------------------------------- programs, and what the device can do

    /** What the device can do: whether to build a ray traced variant, say. */
    public Capabilities capabilities() {
        VulkanDevice device = graph.device();
        return new Capabilities(device.rayTracing(), device.meshShaders(), device.primitiveIds());
    }

    /**
     * The compute program running {@code entry} of the SPIR-V classpath resource {@code shader}, with {@code pushBytes}
     * of push constants and its specialization constants ({@code [vk::constant_id(i)]}, 32-bit) i = 0, 1, ... set to
     * {@code constants}. Built once through the device's pipeline cache and shared by every technique asking for the
     * same; it lives as long as the graph.
     */
    public Pipeline computeProgram(String shader, String entry, int pushBytes, int... constants) {
        ComputeKey key = new ComputeKey(Objects.requireNonNull(shader, "shader"), Objects.requireNonNull(entry, "entry"),
            pushBytes, List.of(Arrays.stream(constants).boxed().toArray(Integer[]::new)));
        return graph.cached(key, device -> {
            try (ShaderModule module = ShaderModule.load(device, shader)) {
                return Pipeline.compute(device, module, entry, pushBytes, constants);
            }
        });
    }

    /** The graphics program {@code program} describes, built and shared as {@link #computeProgram} ones. */
    public Pipeline graphicsProgram(GraphicsProgram program) {
        return graph.cached(Objects.requireNonNull(program, "program"), device -> {
            if (program.stages().isEmpty()) {
                throw new IllegalArgumentException("A graphics program needs shader stages");
            }
            Map<String, ShaderModule> modules = new LinkedHashMap<>();
            try {
                Pipeline.Graphics graphics = Pipeline.graphics(device).pushConstants(program.pushBytes());
                for (GraphicsProgram.Stage stage : program.stages()) {
                    ShaderModule module = modules.computeIfAbsent(stage.shader(), s -> ShaderModule.load(device, s));
                    graphics.stage(stage.kind().bit, module, stage.entry());
                }
                for (Format color : program.colors()) {
                    graphics.color(color.vk());
                }
                if (program.depth() != null) {
                    graphics.depth(program.depth().vk());
                }
                if (program.doubleSided()) {
                    graphics.doubleSided();
                }
                return graphics.build();
            } finally {
                modules.values().forEach(ShaderModule::close);
            }
        });
    }

    private record ComputeKey(String shader, String entry, int pushBytes, List<Integer> constants) {
    }

    /** The device, for the graph's own tests. */
    VulkanDevice device() {
        return graph.device();
    }

    /**
     * The object kept under {@code key}, created with {@code create} the first time; it lives as long as the graph and
     * is closed with it.
     */
    <T extends AutoCloseable> T cached(Object key, Function<VulkanDevice, T> create) {
        return graph.cached(key, create);
    }

    // ---------------------------------------------------------------- results of other techniques

    /**
     * The result {@code key}: built by the technique that provides it, then by every technique that
     * {@link Technique#modifies() modifies} it, in their {@link Order}, the first time it is required.
     */
    public <T> T require(Key<T> key) {
        return key.kind().cast(resolution.require(key));
    }

    /** The result {@code key}, or null if no technique of the graph provides it. */
    public <T> T optional(Key<T> key) {
        return key.kind().cast(resolution.optional(key));
    }

    /**
     * For a technique that {@link Technique#modifies() modifies} {@code key}: the result as the techniques before it
     * left it, now the building technique's to write (in place: e.g. transparency drawn over the scene). It may also
     * {@link #provide provide} another one for the key (e.g. a post effect writing a new image). Those requiring the key
     * get the last version.
     */
    public <T> T modify(Key<T> key) {
        Object result = resolution.take(key);
        if (result instanceof Image image) {
            images.get(image.id).owner = scope();
        } else {
            buffers.get(((Buffer) result).id).owner = scope();
        }
        return key.kind().cast(result);
    }

    /**
     * Provides {@code key}: one of the keys the building technique {@link Technique#provides()}, or a new version of
     * one it {@link #modify modifies}.
     */
    public <T> void provide(Key<T> key, T result) {
        resolution.put(key, key.kind() == Image.class ? image((Image) result) : buffer((Buffer) result));
    }

    /**
     * The result of {@code request}: built now (by the graph's {@link Implementation} for its type, if it has one) if no
     * equal request was built before, otherwise the earlier result, so shared work runs once.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R> R request(Request<R> request) {
        Objects.requireNonNull(request, "request");
        Implementation implementation = implementations.get(request.getClass());
        return resolution.request(request,
            () -> implementation == null ? request.build(this) : (R) implementation.build(request, this));
    }

    // ---------------------------------------------------------------- images

    /**
     * A new image, as {@code desc} describes it, owned by the building technique. Its usage follows from how passes
     * use it; a mipmapped storage image's levels are written one at a time through
     * {@link PassContext#storage(Image, int)}.
     */
    public Image create(String name, ImageDesc desc) {
        Image image = new Image(images.size(), Objects.requireNonNull(name, "name"), Objects.requireNonNull(desc,
            "desc"));
        images.add(new ImageInfo(image, scope(), null));
        return image;
    }

    /**
     * An image kept from frame to frame, as {@code desc} describes it, owned by the building technique: this frame's to
     * write, and last frame's to read. Names are unique within the graph; a history of the same name, descriptor and
     * usage keeps its images and contents when the graph rebuilds.
     */
    public History history(String name, ImageDesc desc) {
        for (History other : histories) {
            if (other.name.equals(name)) {
                throw new IllegalStateException("Two histories are named " + name);
            }
        }
        Image current = create(name, desc);
        Image previous = create(name + " (last frame)", desc);
        History history = new History(name, current, previous);
        images.get(current.id).history = history;
        images.get(previous.id).history = history;
        images.get(previous.id).previous = true;
        histories.add(history);
        return history;
    }

    /** {@code external} as an image of this build, the same one each time. */
    public Image external(ExternalImage external) {
        return externals.computeIfAbsent(external, e -> {
            Image image = new Image(images.size(), e.name, ImageDesc.of(e.format, e.width, e.height));
            images.add(new ImageInfo(image, null, e));
            return image;
        });
    }

    // ---------------------------------------------------------------- buffers

    /**
     * A new transient buffer of {@code size} bytes, owned by the building technique: device-local, reachable by device
     * address ({@link PassContext#address}), its contents undefined until a pass of the frame writes them.
     */
    public Buffer createBuffer(String name, long size) {
        if (size < 1) {
            throw new IllegalArgumentException("Buffer size must be positive: " + name + ", " + size);
        }
        Buffer buffer = new Buffer(buffers.size(), Objects.requireNonNull(name, "name"), size);
        buffers.add(new BufferInfo(buffer, scope()));
        return buffer;
    }

    /**
     * {@code external} as a buffer of this build, the same one each time: passes declare their reads and writes of it
     * as of any buffer, and the graph orders them; its contents outlive the frame.
     */
    public Buffer importBuffer(ExternalBuffer external) {
        return externalBuffers.computeIfAbsent(external, e -> {
            Buffer buffer = new Buffer(buffers.size(), e.name, e.size);
            buffers.add(new BufferInfo(buffer, null, e));
            return buffer;
        });
    }

    private Buffer buffer(Buffer buffer) {
        if (buffer == null || buffer.id >= buffers.size() || buffers.get(buffer.id).buffer != buffer) {
            throw new IllegalArgumentException("Not a buffer of this build: " + buffer);
        }
        return buffer;
    }

    private Image image(Image image) {
        if (image == null || image.id >= images.size() || images.get(image.id).image != image) {
            throw new IllegalArgumentException("Not an image of this build: " + image);
        }
        return image;
    }

    // ---------------------------------------------------------------- passes

    /** Starts a pass; {@link PassBuilder#record} adds it. */
    public PassBuilder pass(String name) {
        return new PassBuilder(Objects.requireNonNull(name, "name"));
    }

    /** Declares a pass's accesses; {@link #record} adds the pass. */
    @AllowAllocation("building the graph, when it changes")
    public final class PassBuilder {
        private final String name;
        private final List<Subresource> used = new ArrayList<>();
        private final List<Boolean> discarded = new ArrayList<>();
        private final List<Attachment> attachments = new ArrayList<>();
        private final List<Access> accesses = new ArrayList<>();
        private final List<Buffer> usedBuffers = new ArrayList<>();
        private final List<BufferAccess> bufferAccesses = new ArrayList<>();
        private boolean sideEffects;
        private boolean async;

        private PassBuilder(String name) {
            this.name = name;
        }

        /** The pass reads all of {@code image}. */
        public PassBuilder read(Image image, Access access) {
            return read(image(image).whole(), access);
        }

        /** The pass reads part of an image: some of its mip levels or layers. */
        public PassBuilder read(Subresource part, Access access) {
            if (access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " writes; declare it with write()");
            }
            return use(part, access);
        }

        /** The pass writes all of {@code image}, which its technique or request made (or an external image). */
        public PassBuilder write(Image image, Access access) {
            return write(image(image).whole(), access);
        }

        /** The pass writes part of an image its technique or request made: some of its mip levels or layers. */
        public PassBuilder write(Subresource part, Access access) {
            if (!access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " only reads; declare it with read()");
            }
            checkWritable(part);
            return use(part, access);
        }

        /** Throws unless the pass may write {@code part}: its technique or request made it, or it is external. */
        private void checkWritable(Subresource part) {
            Image image = part.image();
            ImageInfo info = images.get(image(image).id);
            if (info.previous) {
                throw new IllegalStateException(name + " writes " + image.name + ": last frame's image is read only");
            }
            if (info.external == null && info.owner != scope()) {
                throw new IllegalStateException(name + " writes " + image.name + ", a result of " + name(info.owner)
                    + ": results are written only by whoever makes them");
            }
        }

        /**
         * Renders into {@code target}, a color attachment of the scope the graph opens around the pass: loaded and
         * stored as said. Attachments render in the order attached (location 0 first) and are all of one size.
         */
        public PassBuilder attach(Image target, Load load, Store store) {
            return attach(image(target).whole(), load, store, null);
        }

        /** Renders into {@code target}, cleared to {@code clear} and stored. */
        public PassBuilder attach(Image target, Clear clear) {
            return attach(image(target).whole(), Load.CLEAR, Store.STORE, clear);
        }

        /**
         * Renders into part of an image: one mip level of one layer (a cascade of a shadow map array, a face of a cube
         * map), loaded and stored as said, cleared to {@code clear} with {@link Load#CLEAR}.
         */
        public PassBuilder attach(Subresource target, Load load, Store store, Clear clear) {
            return attachment(target, load, store, clear, false);
        }

        /** Renders depth into {@code target}, loaded and stored as said. */
        public PassBuilder attachDepth(Image target, Load load, Store store) {
            return attachDepth(image(target).whole(), load, store, null);
        }

        /** Renders depth into {@code target}, cleared to {@code clear} and stored. */
        public PassBuilder attachDepth(Image target, Clear clear) {
            return attachDepth(image(target).whole(), Load.CLEAR, Store.STORE, clear);
        }

        /** Renders depth into part of an image, as {@link #attach(Subresource, Load, Store, Clear)} color. */
        public PassBuilder attachDepth(Subresource target, Load load, Store store, Clear clear) {
            return attachment(target, load, store, clear, true);
        }

        /**
         * Resolves the last color attachment, multisampled, into {@code target} (one sample, its size and format) when
         * the pass stops rendering.
         */
        public PassBuilder resolve(Image target) {
            Attachment last = attachments.isEmpty() ? null : attachments.getLast();
            if (last == null || last.depth() || last.target().image().desc().samples() == 1 || last.resolve() != null) {
                throw new IllegalStateException(name + ": resolve follows a multisampled color attachment");
            }
            Subresource into = image(target).whole();
            if (target.desc().samples() != 1 || target.width != last.target().image().width
                || target.height != last.target().image().height || target.format != last.target().image().format) {
                throw new IllegalArgumentException(name + ": " + target + " cannot take the resolve of "
                    + last.target() + ": one sample, its size and format");
            }
            attachments.set(attachments.size() - 1, new Attachment(last.target(), last.load(), last.store(),
                last.clear(), false, into));
            checkWritable(into);
            return use(into, Access.COLOR_ATTACHMENT, true);
        }

        private PassBuilder attachment(Subresource target, Load load, Store store, Clear clear, boolean depth) {
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
            if ((load == Load.CLEAR) != (clear != null) || clear != null && clear.depth != depth) {
                throw new IllegalArgumentException(name + ": " + target + " is cleared with a " + (depth ? "depth"
                    : "color") + " clear value exactly when it loads with CLEAR");
            }
            if (target.image().mips != 1 || !target.oneLayer()) {
                throw new IllegalArgumentException(name + ": an attachment is one layer of an image of one mip level: "
                    + target);
            }
            Image first = attachments.isEmpty() ? null : attachments.getFirst().target().image();
            if (first != null && (first.width != target.image().width || first.height != target.image().height)) {
                throw new IllegalArgumentException(name + ": attachments are of one size: " + target + " and " + first);
            }
            if (depth && attachments.stream().anyMatch(Attachment::depth)) {
                throw new IllegalStateException(name + " has a depth attachment already");
            }
            if (depth != target.image().format.depth()) {
                throw new IllegalArgumentException(name + ": " + target + " is " + (depth ? "not " : "")
                    + "a depth format");
            }
            checkWritable(target);
            attachments.add(new Attachment(target, load, store, clear, depth, null));
            return use(target, depth ? Access.DEPTH_ATTACHMENT : Access.COLOR_ATTACHMENT, load != Load.LOAD);
        }

        /** The pass reads {@code buffer}. */
        public PassBuilder read(Buffer buffer, BufferAccess access) {
            if (access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " writes; declare it with write()");
            }
            return use(buffer, access);
        }

        /** The pass writes {@code buffer}, which its technique or request made, or an imported one. */
        public PassBuilder write(Buffer buffer, BufferAccess access) {
            if (!access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " only reads; declare it with read()");
            }
            BufferInfo info = buffers.get(buffer(buffer).id);
            if (info.external == null && info.owner != scope()) {
                throw new IllegalStateException(name + " writes " + buffer.name + ", a result of " + name(info.owner)
                    + ": results are written only by whoever makes them");
            }
            return use(buffer, access);
        }

        private PassBuilder use(Buffer buffer, BufferAccess access) {
            usedBuffers.add(buffer(buffer));
            bufferAccesses.add(Objects.requireNonNull(access, "access"));
            buffers.get(buffer.id).usage |= access.usage;
            return this;
        }

        private PassBuilder use(Subresource part, Access access) {
            return use(part, access, false);
        }

        private PassBuilder use(Subresource part, Access access, boolean discard) {
            used.add(part);
            discarded.add(discard);
            accesses.add(Objects.requireNonNull(access, "access"));
            images.get(image(part.image()).id).usage |= access.usage;
            return this;
        }

        /** The pass does something beyond its images (a readback, a buffer for the CPU): it is never culled. */
        public PassBuilder sideEffects() {
            sideEffects = true;
            return this;
        }

        /**
         * The pass may run on the async compute queue, beside graphics work it does not depend on: compute, sampling,
         * storage and transfer accesses only (no attachments, no external images, no index buffers or draw shaders).
         * Where the device has no separate compute queue, or async compute is off, it runs on the graphics queue in its
         * place.
         */
        public PassBuilder async() {
            async = true;
            return this;
        }

        /** Adds the pass, recording with {@code body}. */
        public void record(PassBody body) {
            int[] ids = new int[used.size()];
            int[] ranges = new int[4 * used.size()];
            boolean[] discards = new boolean[used.size()];
            for (int i = 0; i < ids.length; i++) {
                Subresource part = used.get(i);
                ids[i] = part.image().id;
                ranges[4 * i] = part.baseMip();
                ranges[4 * i + 1] = part.mipCount();
                ranges[4 * i + 2] = part.baseLayer();
                ranges[4 * i + 3] = part.layerCount();
                discards[i] = discarded.get(i);
            }
            int[] bufferIds = new int[usedBuffers.size()];
            for (int i = 0; i < bufferIds.length; i++) {
                bufferIds[i] = usedBuffers.get(i).id;
            }
            passes.add(new PassInfo(name, scope() == null ? "the graph" : name(scope()), ids,
                accesses.toArray(new Access[0]), ranges, discards, attachments.toArray(new Attachment[0]), bufferIds, bufferAccesses.toArray(new BufferAccess[0]), sideEffects,
                async, Objects.requireNonNull(body, "body")));
        }
    }

    static String name(Object owner) {
        if (owner == null) {
            return "the graph";
        }
        if (owner instanceof Request<?> request) {
            return "request " + request;
        }
        return owner.getClass().getSimpleName().isEmpty() ? owner.toString() : owner.getClass().getSimpleName();
    }
}
