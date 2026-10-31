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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
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
        final Object scope; // what recorded it (a technique or request), its view (an output's copy), or null: the graph

        PassInfo(String name, String owner, Object scope, int[] images, Access[] accesses, int[] ranges,
            boolean[] discards, Attachment[] attachments, int[] buffers, BufferAccess[] bufferAccesses,
            boolean sideEffects, boolean async, PassBody body) {
            this.name = name;
            this.owner = owner;
            this.scope = scope;
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
    private final List<PassBuilder> openPasses = new ArrayList<>();
    final List<ReadbackPlan> readbacks = new ArrayList<>();
    private final Resolution<Object, Technique> resolution;
    private final List<ViewPort> views;
    private final List<Technique> techniques;
    private final Map<Technique, ViewPort> viewOf = new IdentityHashMap<>();
    private final Set<Object> provided = new HashSet<>(); // every key provided, as scoped
    private final Map<Object, ViewPort> requestViews = new HashMap<>(); // the view each request was first made in
    private final Map<Object, List<Object>> requesters = new HashMap<>(); // the scopes making each request
    private final List<Running> running = new ArrayList<>(); // handed out while building, resolved when it ends
    private final Map<Scoped, Scoped> interned = new HashMap<>();
    private final Map<ExternalImage, Image> externals = new IdentityHashMap<>();
    /** Imported buffers by Vulkan handle: every wrapper of one buffer is the same buffer of the build. */
    private final Map<Long, Buffer> externalBuffers = new HashMap<>();

    private final Map<Class<?>, Implementation<?, ?>> implementations;

    private final FrameGraph graph;

    /** A key of a view: its techniques' version of {@code key}, apart from other views' and the frame's. */
    record Scoped(ViewPort view, Key<?> key) {
        @Override
        public String toString() {
            return key + " of " + view;
        }
    }

    /** {@code key} of {@code view}, as the build resolves it: one instance each, as the resolution keys by identity. */
    Object scoped(ViewPort view, Key<?> key) {
        return interned.computeIfAbsent(new Scoped(view, key), s -> s);
    }

    GraphBuilder(FrameGraph graph, List<Technique> techniques, Map<Class<?>, Implementation<?, ?>> implementations) {
        this(graph, techniques, List.of(), implementations);
    }

    GraphBuilder(FrameGraph graph, List<Technique> frame, List<ViewPort> views,
        Map<Class<?>, Implementation<?, ?>> implementations) {
        this.graph = graph;
        this.implementations = implementations;
        this.views = List.copyOf(views);
        List<Technique> all = new ArrayList<>(frame);
        // Modifier order within each scope: the frame's, then each view's (a view's may share ids with another's).
        Map<Technique, Integer> positions = new IdentityHashMap<>(positions(frame));
        for (ViewPort view : views) {
            Map<Technique, Integer> local = positions(view.techniques);
            for (Technique technique : view.techniques) {
                viewOf.put(technique, view);
                positions.put(technique, all.size() + local.get(technique));
            }
            all.addAll(view.techniques);
        }
        this.techniques = List.copyOf(all);
        for (Technique technique : all) {
            for (Key<?> key : technique.provides()) {
                provided.add(inScope(viewOf.get(technique), key));
            }
        }
        this.resolution = new Resolution<>(all, new Resolution.Producers<>() {
            @Override
            public List<Object> provides(Technique technique) {
                List<Object> keys = new ArrayList<>();
                for (Key<?> key : technique.provides()) {
                    keys.add(inScope(viewOf.get(technique), key));
                }
                return keys;
            }

            @Override
            public List<Object> modifies(Technique technique) {
                List<Object> keys = new ArrayList<>();
                for (Key<?> key : technique.modifies()) {
                    keys.add(resolve(viewOf.get(technique), key));
                }
                return keys;
            }

            @Override
            public void build(Technique technique) {
                technique.build(GraphBuilder.this);
            }

            @Override
            public String name(Technique technique) {
                ViewPort view = viewOf.get(technique);
                return GraphBuilder.name(technique) + (view == null ? "" : " of " + view);
            }
        }, Comparator.comparingInt(positions::get), "technique");
    }

    /** The internal key of {@code key} provided in {@code view}'s scope (null: the frame's). */
    private Object inScope(ViewPort view, Key<?> key) {
        return view == null ? key : scoped(view, key);
    }

    /** {@code key} as seen from {@code view} (null: the frame): the view's version where it provides one. */
    private Object resolve(ViewPort view, Key<?> key) {
        if (view != null) {
            Object scoped = scoped(view, key);
            if (provided.contains(scoped)) {
                return scoped;
            }
        }
        return key;
    }

    /** Every technique of the build: the frame's, then each view's. */
    List<Technique> techniques() {
        return techniques;
    }

    /**
     * The view being built: the building technique's, or for a request the view of whoever first requested it; null
     * for the frame's techniques.
     */
    public ViewPort view() {
        Object scope = scope();
        if (scope instanceof Technique technique) {
            return viewOf.get(technique);
        }
        return scope == null ? null : requestViews.get(scope);
    }

    /** An output's image: {@code key}, the frame's or a view's ({@link #scoped}). */
    Image output(Object key) {
        return (Image) resolution.require(key);
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

    // ---------------------------------------------------------------- readbacks

    /**
     * Copies {@code bytes} at {@code offset} of {@code from} off the GPU each frame, for {@code receiver}, which gets
     * them once the GPU finished the frame (a frame or more later, on the render thread): GPU culling statistics, a
     * picking result. The copy is a pass of its own, after {@code from}'s writers.
     */
    public void readback(String name, Buffer from, long offset, long bytes, Readback receiver) {
        Objects.requireNonNull(receiver, "receiver");
        if (offset < 0 || bytes <= 0 || offset + bytes > from.size()) {
            throw new IllegalArgumentException("Reading back " + bytes + " bytes at " + offset + " of " + from);
        }
        ReadbackPlan plan = new ReadbackPlan(receiver, bytes);
        readbacks.add(plan);
        pass(name).read(from, BufferAccess.TRANSFER_SRC).sideEffects().record(ctx -> ctx.readback(plan, from, offset));
    }

    /**
     * Copies one mip level of an image (its layers) off the GPU each frame, tightly packed, for {@code receiver}, as
     * {@link #readback(String, Buffer, long, long, Readback)} does: a screenshot, a probe of the depth.
     */
    public void readback(String name, Subresource from, Readback receiver) {
        Objects.requireNonNull(receiver, "receiver");
        if (!from.oneMip()) {
            throw new IllegalArgumentException("Reading back " + from + ": one mip level");
        }
        Image image = from.image();
        long bytes = (long) image.format.bytes() * Math.max(1, image.width >> from.baseMip())
            * Math.max(1, image.height >> from.baseMip()) * from.layerCount();
        ReadbackPlan plan = new ReadbackPlan(receiver, bytes);
        readbacks.add(plan);
        pass(name).read(from, Access.TRANSFER_SRC).sideEffects().record(ctx -> ctx.readback(plan, from));
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
        return key.kind().cast(resolution.require(resolve(view(), key)));
    }

    /** The result {@code key}, or null if no technique of the graph provides it. */
    public <T> T optional(Key<T> key) {
        return key.kind().cast(resolution.optional(resolve(view(), key)));
    }

    /**
     * View {@code view}'s result {@code key} (or the frame's where it provides none), e.g. a mirror's image for the
     * main view's shading. The pass reading it orders after whatever writes it, whichever view.
     */
    public <T> T require(ViewPort view, Key<T> key) {
        return key.kind().cast(resolution.require(resolve(Objects.requireNonNull(view, "view"), key)));
    }

    /** Every view's result {@code key}, in the order the views were added, for those providing one. */
    public <T> List<T> requireAll(Key<T> key) {
        List<T> all = new ArrayList<>();
        for (ViewPort view : views) {
            Object scoped = scoped(view, key);
            if (provided.contains(scoped)) {
                all.add(key.kind().cast(resolution.require(scoped)));
            }
        }
        return all;
    }

    /**
     * For a technique that {@link Technique#modifies() modifies} {@code key}: the result as the techniques before it
     * left it, now the building technique's to write (in place: e.g. transparency drawn over the scene). It may also
     * {@link #provide provide} another one for the key (e.g. a post effect writing a new image). Those requiring the key
     * get the last version.
     */
    public <T> T modify(Key<T> key) {
        Object result = resolution.take(resolve(view(), key));
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
        if (key.kind() == Buffer.class) {
            BufferInfo info = buffers.get(buffer((Buffer) result).id);
            if (info.external != null && info.owner == null) {
                info.owner = scope(); // an import provided under a key: from now on its provider's to write
            }
        }
        resolution.put(resolve(view(), key),
            key.kind() == Image.class ? image((Image) result) : buffer((Buffer) result));
    }

    /**
     * The result of {@code request}: built now (by the graph's {@link Implementation} for its type, if it has one) if no
     * equal request was built before, otherwise the earlier result, so shared work runs once.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R> R request(Request<R> request) {
        Objects.requireNonNull(request, "request");
        requestViews.putIfAbsent(request, view()); // built in the view of whoever requests it first
        requesters.computeIfAbsent(request, r -> new ArrayList<>()).add(scope());
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
     * usage keeps its images and contents when the graph rebuilds. A view's histories are its own: their names are
     * qualified by the view's.
     */
    public History history(String name, ImageDesc desc) {
        ViewPort view = view();
        String key = view == null ? name : view.name() + ": " + name;
        for (History other : histories) {
            if (other.key.equals(key)) {
                throw new IllegalStateException("Two histories are named " + key);
            }
        }
        Image current = create(key, desc);
        Image previous = create(key + " (last frame)", desc);
        History history = new History(name, key, current, previous);
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
     * {@code external} as a buffer of this build, the same one each time, and for every {@code ExternalBuffer} of the
     * same GPU buffer (so a game importing the engine's instances is ordered with the engine's passes): passes declare
     * their reads and writes of it as of any buffer, and the graph orders them; its contents outlive the frame. Any pass
     * may write it, unless a technique {@link #provide provides} it under a key: then, as any result, only that technique
     * and those modifying the key.
     */
    public Buffer importBuffer(ExternalBuffer external) {
        return externalBuffers.computeIfAbsent(external.handle, h -> {
            Buffer buffer = new Buffer(buffers.size(), external.name, external.size);
            buffers.add(new BufferInfo(buffer, null, external));
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
        private boolean open;
        private int index = -1; // in the build's passes, once recorded
        private PassBody body;
        private String owner;
        private Object scope;

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

        /**
         * The pass writes {@code buffer}, which its technique or request made, or an imported one no technique provides
         * under a key (or one its technique provides, or modifies).
         */
        public PassBuilder write(Buffer buffer, BufferAccess access) {
            if (!access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " only reads; declare it with read()");
            }
            BufferInfo info = buffers.get(buffer(buffer).id);
            if ((info.external == null || info.owner != null) && info.owner != scope()) {
                throw new IllegalStateException(name + " writes " + buffer.name + ", a result of " + name(info.owner)
                    + ": results are written only by whoever makes them");
            }
            return use(buffer, access);
        }

        private PassBuilder use(Buffer buffer, BufferAccess access) {
            checkDeclarable();
            usedBuffers.add(buffer(buffer));
            bufferAccesses.add(Objects.requireNonNull(access, "access"));
            buffers.get(buffer.id).usage |= access.usage;
            return this;
        }

        private PassBuilder use(Subresource part, Access access) {
            return use(part, access, false);
        }

        private PassBuilder use(Subresource part, Access access, boolean discard) {
            checkDeclarable();
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

        /**
         * Lets the pass declare more after {@link #record}, until the build ends: for a pass whose inputs other
         * techniques add while the graph builds, such as one culling every view drawn in the frame.
         */
        public PassBuilder open() {
            open = true;
            return this;
        }

        /** Adds the pass, recording with {@code body}. */
        public void record(PassBody body) {
            if (index >= 0) {
                throw new IllegalStateException(name + " is recorded already");
            }
            this.body = Objects.requireNonNull(body, "body");
            if (scope == null) {
                scope = scope();
            }
            owner = scope() == null ? "the graph" : name(scope());
            index = passes.size();
            passes.add(info());
            if (open) {
                openPasses.add(this);
            }
        }

        /** The pass is {@code view}'s (null: the frame's), whoever records it: an output's copy, say. */
        PassBuilder in(ViewPort view) {
            scope = view;
            return this;
        }

        /** Throws once the pass is recorded, unless it is {@link #open}. */
        private void checkDeclarable() {
            if (index >= 0 && !open) {
                throw new IllegalStateException(name + " is recorded: declare before record(), or open() it");
            }
        }

        /** The pass as declared so far. */
        private PassInfo info() {
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
            return new PassInfo(name, owner, scope, ids, accesses.toArray(new Access[0]), ranges, discards,
                attachments.toArray(new Attachment[0]), bufferIds, bufferAccesses.toArray(new BufferAccess[0]),
                sideEffects, async, body);
        }
    }

    /** Ends the build: the {@link PassBuilder#open open} passes take what was declared after they were recorded. */
    void finishOpenPasses() {
        for (PassBuilder pass : openPasses) {
            passes.set(pass.index, pass.info());
        }
        openPasses.clear();
    }

    /**
     * Whether the building technique or request runs in the frame being recorded, for work at record time that its
     * passes alone do not cover, such as a pass shared with other views leaving out this one's part: false while every
     * view it runs for is skipped ({@link ViewPort#enabled}, {@link ViewPort#rate}); a frame technique's is always
     * true. Known once the build ends; reading it allocates nothing.
     */
    public BooleanSupplier running() {
        Running supplier = new Running(scope());
        running.add(supplier);
        return supplier;
    }

    /** Resolves what {@link #running} handed out: the build has ended. */
    void finishRunning() {
        for (Running supplier : running) {
            supplier.views = runsFor(supplier.scope);
        }
    }

    /** {@link #running}'s answer: whether any of the views its scope runs for runs this frame. */
    private static final class Running implements BooleanSupplier {
        private final Object scope;
        private ViewPort[] views; // null: every frame

        Running(Object scope) {
            this.scope = scope;
        }

        @Override
        public boolean getAsBoolean() {
            return FrameGraph.runs(views);
        }
    }

    /**
     * The views whose frames need what {@code scope} records, or null if every frame does: a technique's view, an
     * output's view, or for a request every view that made it (null if the frame or the graph made it too).
     */
    @AllowAllocation("building the graph, when it changes")
    ViewPort[] runsFor(Object scope) {
        Set<ViewPort> views = new LinkedHashSet<>();
        return addViews(scope, views) ? views.toArray(new ViewPort[0]) : null;
    }

    /** Adds the views {@code scope} runs for to {@code views}; false if it runs every frame. */
    @AllowAllocation("building the graph, when it changes")
    private boolean addViews(Object scope, Set<ViewPort> views) {
        ViewPort view = scope instanceof ViewPort port ? port
            : scope instanceof Technique technique ? viewOf.get(technique) : null;
        if (view != null) {
            views.add(view);
            return true;
        }
        List<Object> made = scope == null || scope instanceof Technique ? null : requesters.get(scope);
        if (made == null) {
            return false; // the graph's, or a frame technique's
        }
        for (Object requester : made) {
            if (!addViews(requester, views)) {
                return false;
            }
        }
        return true;
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
