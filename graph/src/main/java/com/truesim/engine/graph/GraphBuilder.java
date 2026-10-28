package com.truesim.engine.graph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What techniques and requests build with: images, passes, and the results of other techniques. Requiring a result
 * builds the technique that provides it, the first time, so providers' passes always come before their consumers', and
 * techniques nothing needs are never built. Passes run in the order they are added; their declared accesses decide the
 * barriers.
 *
 * <p>Results are single owner: a pass may write only images its own technique or request made (or external images),
 * so whoever requires a result sees it complete.
 */
@AllowAllocation("building the graph, when it changes")
public final class GraphBuilder {

    /** One image of the build. */
    static final class ImageInfo {
        final Image image;
        final Object owner;
        final ExternalImage external;
        int usage;

        ImageInfo(Image image, Object owner, ExternalImage external) {
            this.image = image;
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
        final boolean sideEffects;
        final boolean async;
        final PassBody body;

        PassInfo(String name, String owner, int[] images, Access[] accesses, boolean sideEffects, boolean async,
            PassBody body) {
            this.name = name;
            this.owner = owner;
            this.images = images;
            this.accesses = accesses;
            this.sideEffects = sideEffects;
            this.async = async;
            this.body = body;
        }
    }

    final List<ImageInfo> images = new ArrayList<>();
    final List<PassInfo> passes = new ArrayList<>();
    private final Map<Key, Technique> providers = new IdentityHashMap<>();
    private final Map<Key, Image> provided = new IdentityHashMap<>();
    private final Set<Technique> built = new HashSet<>();
    private final Deque<Technique> building = new ArrayDeque<>();
    private final Map<Request<?>, Object> requests = new HashMap<>();
    private final Map<ExternalImage, Image> externals = new IdentityHashMap<>();
    private Object scope;

    private final Map<Class<?>, Implementation<?, ?>> implementations;

    private final FrameGraph graph;

    GraphBuilder(FrameGraph graph, List<Technique> techniques, Map<Class<?>, Implementation<?, ?>> implementations) {
        this.graph = graph;
        this.implementations = implementations;
        for (Technique technique : techniques) {
            for (Key key : technique.provides()) {
                Technique other = providers.putIfAbsent(key, technique);
                if (other != null) {
                    throw new IllegalStateException("Two techniques provide " + key + ": " + name(other) + " and "
                        + name(technique));
                }
            }
        }
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

    /** The result {@code key}, building the technique that provides it if it has not been built yet. */
    public Image require(Key key) {
        Image image = optional(key);
        if (image == null) {
            throw new IllegalStateException("No technique provides " + key + (scope == null ? "" : ", which "
                + name(scope) + " requires"));
        }
        return image;
    }

    /** The result {@code key}, or null if no technique of the graph provides it. */
    public Image optional(Key key) {
        Image image = provided.get(key);
        if (image != null) {
            return image;
        }
        Technique technique = providers.get(key);
        if (technique == null) {
            return null;
        }
        if (building.contains(technique)) {
            List<String> chain = new ArrayList<>();
            building.descendingIterator().forEachRemaining(t -> chain.add(name(t)));
            chain.add(name(technique));
            throw new IllegalStateException("Techniques require each other's results in a cycle: "
                + String.join(" -> ", chain) + " (for " + key + ")");
        }
        build(technique);
        return provided.get(key);
    }

    void build(Technique technique) {
        if (!built.add(technique)) {
            return;
        }
        Object outer = scope;
        building.push(technique);
        scope = technique;
        technique.build(this);
        scope = outer;
        building.pop();
        List<Key> missing = technique.provides().stream().filter(key -> !provided.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(name(technique) + " did not provide " + missing);
        }
    }

    /** Provides {@code key}: one of the keys the building technique {@link Technique#provides()}. */
    public void provide(Key key, Image image) {
        if (!(scope instanceof Technique technique) || !technique.provides().contains(key)) {
            throw new IllegalStateException((scope == null ? "Nothing" : name(scope)) + " cannot provide " + key
                + ": only the technique listing it in provides() can");
        }
        if (provided.putIfAbsent(key, image(image)) != null) {
            throw new IllegalStateException(name(scope) + " provides " + key + " twice");
        }
    }

    /**
     * The result of {@code request}: built now (by the graph's {@link Implementation} for its type, if it has one) if no
     * equal request was built before, otherwise the earlier result, so shared work runs once.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R> R request(Request<R> request) {
        Objects.requireNonNull(request, "request");
        if (requests.containsKey(request)) {
            Object result = requests.get(request);
            if (result == null) {
                throw new IllegalStateException("The request " + request + " needs its own result");
            }
            return (R) result;
        }
        requests.put(request, null); // in progress: an equal request inside it is a cycle
        Object outer = scope;
        scope = request;
        Implementation implementation = implementations.get(request.getClass());
        R result = Objects.requireNonNull(implementation == null ? request.build(this)
            : (R) implementation.build(request, this), "A request's result");
        scope = outer;
        requests.put(request, result);
        return result;
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
        images.add(new ImageInfo(image, scope, null));
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

    /** {@code external} as an image of this build, the same one each time. */
    public Image external(ExternalImage external) {
        return externals.computeIfAbsent(external, e -> {
            Image image = new Image(images.size(), e.name, e.width, e.height, e.format, 1);
            images.add(new ImageInfo(image, null, e));
            return image;
        });
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
            if (info.external == null && info.owner != scope) {
                throw new IllegalStateException(name + " writes " + image.name + ", a result of " + name(info.owner)
                    + ": results are written only by whoever makes them");
            }
            return use(image, access);
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
         * storage and transfer accesses only (no attachments, no external images). Where the device has no separate
         * compute queue, or async compute is off, it runs on the graphics queue in its place.
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
            passes.add(new PassInfo(name, scope == null ? "the graph" : name(scope), ids,
                accesses.toArray(new Access[0]), sideEffects, async, Objects.requireNonNull(body, "body")));
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
