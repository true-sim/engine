package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Resolution;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
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

    /** One pass of the build: its accesses (image id and access, in pairs) and body. */
    static final class PassInfo {
        final String name;
        final String owner;
        final int[] images;
        final Access[] accesses;
        final int[] buffers;
        final BufferAccess[] bufferAccesses;
        final boolean sideEffects;
        final boolean async;
        final PassBody body;

        PassInfo(String name, String owner, int[] images, Access[] accesses, int[] buffers,
            BufferAccess[] bufferAccesses, boolean sideEffects, boolean async, PassBody body) {
            this.name = name;
            this.owner = owner;
            this.images = images;
            this.accesses = accesses;
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
    private final Resolution<Key, Technique> resolution;
    private final Map<ExternalImage, Image> externals = new IdentityHashMap<>();
    private final Map<ExternalBuffer, Buffer> externalBuffers = new IdentityHashMap<>();

    private final Map<Class<?>, Implementation<?, ?>> implementations;

    private final FrameGraph graph;

    GraphBuilder(FrameGraph graph, List<Technique> techniques, Map<Class<?>, Implementation<?, ?>> implementations) {
        this.graph = graph;
        this.implementations = implementations;
        this.resolution = new Resolution<>(techniques, new Resolution.Producers<>() {
            @Override
            public List<Key> provides(Technique technique) {
                return technique.provides();
            }

            @Override
            public List<Key> modifies(Technique technique) {
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
        }, Comparator.comparingInt(Technique::stage), "technique"); // stable: equal stages as added
    }

    /** What is being built: a technique, a request, or null (the graph itself). What it makes belongs to it. */
    private Object scope() {
        return resolution.scope();
    }

    // ---------------------------------------------------------------- the device, and objects kept across builds

    /** The device the graph records for, e.g. to create pipelines. */
    public VulkanDevice device() {
        return graph.device();
    }

    /**
     * The object kept under {@code key} (pipelines, samplers: what passes need, made once), created with
     * {@code create} the first time. It lives as long as the graph and is closed with it.
     */
    public <T extends AutoCloseable> T cached(Object key, Function<VulkanDevice, T> create) {
        return graph.cached(key, create);
    }

    // ---------------------------------------------------------------- results of other techniques

    /**
     * The result {@code key}, an image: built by the technique that provides it, then by every technique that
     * {@link Technique#modifies() modifies} it, in stage order, the first time it is required.
     */
    public Image require(Key key) {
        return image(key, resolution.require(key));
    }

    /** The image result {@code key}, or null if no technique of the graph provides it. */
    public Image optional(Key key) {
        Object result = resolution.optional(key);
        return result == null ? null : image(key, result);
    }

    /** The result {@code key}, a buffer (e.g. a light list), as {@link #require} gives images. */
    public Buffer requireBuffer(Key key) {
        return buffer(key, resolution.require(key));
    }

    /** The buffer result {@code key}, or null if no technique of the graph provides it. */
    public Buffer optionalBuffer(Key key) {
        Object result = resolution.optional(key);
        return result == null ? null : buffer(key, result);
    }

    /**
     * For a technique that {@link Technique#modifies() modifies} {@code key}: the image as the techniques before it
     * left it, now the building technique's to write (in place: e.g. transparency drawn over the scene). It may also
     * {@link #provide(Key, Image) provide} another image for the key (e.g. a post effect writing a new one). Those
     * requiring the key get the last version.
     */
    public Image modify(Key key) {
        return image(key, take(key));
    }

    /** {@link #modify} for a buffer result. */
    public Buffer modifyBuffer(Key key) {
        return buffer(key, take(key));
    }

    private Image image(Key key, Object result) {
        if (!(result instanceof Image image)) {
            throw new IllegalStateException(key + " is a buffer, not an image");
        }
        return image;
    }

    private Buffer buffer(Key key, Object result) {
        if (!(result instanceof Buffer buffer)) {
            throw new IllegalStateException(key + " is an image, not a buffer");
        }
        return buffer;
    }

    /** {@code key}'s result, now the building technique's: it writes it from here on. */
    private Object take(Key key) {
        Object result = resolution.take(key);
        if (result instanceof Image image) {
            images.get(image.id).owner = scope();
        } else {
            buffers.get(((Buffer) result).id).owner = scope();
        }
        return result;
    }

    /**
     * Provides {@code key}: one of the keys the building technique {@link Technique#provides()}, or a new version of
     * one it {@link #modify modifies}.
     */
    public void provide(Key key, Image image) {
        resolution.put(key, image(image));
    }

    /** Provides {@code key} as a buffer, as {@link #provide(Key, Image)} does an image. */
    public void provide(Key key, Buffer buffer) {
        resolution.put(key, buffer(buffer));
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

    /** A new image, {@code width} by {@code height} {@code format} texels, owned by the building technique. */
    public Image create(String name, int width, int height, int format) {
        return create(name, width, height, format, 1);
    }

    /**
     * A new image of {@code mips} mip levels (up to {@code GpuImage.fullMipCount}), owned by the building technique.
     * Passes write a level as a storage image through {@link PassContext#storage(Image, int)}.
     */
    public Image create(String name, int width, int height, int format, int mips) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image size must be positive: " + name + ", " + width + " x " + height);
        }
        if (mips < 1 || mips > GpuImage.fullMipCount(width, height)) {
            throw new IllegalArgumentException(name + " has 1 to " + GpuImage.fullMipCount(width, height)
                + " mip levels: " + mips);
        }
        Image image = new Image(images.size(), name, width, height, format, mips);
        images.add(new ImageInfo(image, scope(), null));
        return image;
    }

    /** A new image of {@code like}'s format, {@code scale} times its size (at least 1 texel). */
    public Image createLike(String name, Image like, double scale) {
        return createLike(name, like, scale, like.format);
    }

    /** A new image of {@code format}, {@code scale} times {@code like}'s size (at least 1 texel). */
    public Image createLike(String name, Image like, double scale, int format) {
        if (!(scale > 0)) {
            throw new IllegalArgumentException("The scale must be positive: " + scale);
        }
        return create(name, Math.max(1, (int) Math.round(like.width * scale)),
            Math.max(1, (int) Math.round(like.height * scale)), format);
    }

    /**
     * An image kept from frame to frame, {@code width} by {@code height} {@code format} texels, owned by the building
     * technique: this frame's to write, and last frame's to read. Names are unique within the graph; a history of the
     * same name, size and format keeps its images when the graph rebuilds (its contents start over).
     */
    public History history(String name, int width, int height, int format) {
        for (History other : histories) {
            if (other.name.equals(name)) {
                throw new IllegalStateException("Two histories are named " + name);
            }
        }
        Image current = create(name, width, height, format);
        Image previous = create(name + " (last frame)", width, height, format);
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
            Image image = new Image(images.size(), e.name, e.width, e.height, e.format, 1);
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
        private final List<Image> used = new ArrayList<>();
        private final List<Access> accesses = new ArrayList<>();
        private final List<Buffer> usedBuffers = new ArrayList<>();
        private final List<BufferAccess> bufferAccesses = new ArrayList<>();
        private boolean sideEffects;
        private boolean async;

        private PassBuilder(String name) {
            this.name = name;
        }

        /** The pass reads {@code image}. */
        public PassBuilder read(Image image, Access access) {
            if (access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " writes; declare it with write()");
            }
            return use(image, access);
        }

        /** The pass writes {@code image}, which its technique or request made (or an external image). */
        public PassBuilder write(Image image, Access access) {
            if (!access.writes) {
                throw new IllegalArgumentException(name + ": " + access + " only reads; declare it with read()");
            }
            ImageInfo info = images.get(image(image).id);
            if (info.previous) {
                throw new IllegalStateException(name + " writes " + image.name + ": last frame's image is read only");
            }
            if (info.external == null && info.owner != scope()) {
                throw new IllegalStateException(name + " writes " + image.name + ", a result of " + name(info.owner)
                    + ": results are written only by whoever makes them");
            }
            return use(image, access);
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

        private PassBuilder use(Image image, Access access) {
            used.add(image(image));
            accesses.add(Objects.requireNonNull(access, "access"));
            images.get(image.id).usage |= access.usage;
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
            for (int i = 0; i < ids.length; i++) {
                ids[i] = used.get(i).id;
            }
            int[] bufferIds = new int[usedBuffers.size()];
            for (int i = 0; i < bufferIds.length; i++) {
                bufferIds[i] = usedBuffers.get(i).id;
            }
            passes.add(new PassInfo(name, scope() == null ? "the graph" : name(scope()), ids,
                accesses.toArray(new Access[0]), bufferIds, bufferAccesses.toArray(new BufferAccess[0]), sideEffects,
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
