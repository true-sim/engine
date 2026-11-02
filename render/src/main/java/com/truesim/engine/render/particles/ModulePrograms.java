package com.truesim.engine.render.particles;

import static com.truesim.engine.render.layout.ParticleConstants.BINDING_WORDS;
import static com.truesim.engine.render.layout.ParticleConstants.COLOR_WORDS;
import static com.truesim.engine.render.layout.ParticleConstants.CURVE_EVEN;
import static com.truesim.engine.render.layout.ParticleConstants.DRAW_BLEND_FRAMES;
import static com.truesim.engine.render.layout.ParticleConstants.DRAW_LOOP;
import static com.truesim.engine.render.layout.ParticleConstants.DRAW_VOLUME;
import static com.truesim.engine.render.layout.ParticleConstants.OP_BOX;
import static com.truesim.engine.render.layout.ParticleConstants.OP_COLOR;
import static com.truesim.engine.render.layout.ParticleConstants.OP_CONE;
import static com.truesim.engine.render.layout.ParticleConstants.OP_DISC;
import static com.truesim.engine.render.layout.ParticleConstants.OP_DRAG;
import static com.truesim.engine.render.layout.ParticleConstants.OP_GRAVITY;
import static com.truesim.engine.render.layout.ParticleConstants.OP_LIFETIME;
import static com.truesim.engine.render.layout.ParticleConstants.OP_OVER_COLOR;
import static com.truesim.engine.render.layout.ParticleConstants.OP_OVER_SIZE;
import static com.truesim.engine.render.layout.ParticleConstants.OP_POINT;
import static com.truesim.engine.render.layout.ParticleConstants.OP_SIZE;
import static com.truesim.engine.render.layout.ParticleConstants.OP_SPEED;
import static com.truesim.engine.render.layout.ParticleConstants.OP_SPHERE;
import static com.truesim.engine.render.layout.ParticleConstants.OP_VELOCITY;
import static com.truesim.engine.render.layout.ParticleConstants.PROGRAM_OVER;
import static com.truesim.engine.render.layout.ParticleConstants.PROGRAM_TRAIL;
import static com.truesim.engine.render.layout.ParticleConstants.RENDERER_WORDS;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.RangeAllocator;
import com.truesim.engine.render.layout.ParticleConstants;
import com.truesim.engine.render.layout.ParticleRendererLayout;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.effects.Binding;
import com.truesim.scene.effects.ColorBinding;
import com.truesim.scene.effects.Capability;
import com.truesim.scene.effects.Collide;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.CpuSimulation;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EmitterData;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.ModuleRegistry;
import com.truesim.scene.effects.ModuleType;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Target;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The module programs (E4) and curve keys (E18) of the effects playing on the GPU: each {@link EffectData} encoded once
 * into a block of program words, an emitter's initializers and updaters each, and a block of curve keys, shared by every
 * instance of the effect and freed once none plays it and no frame in flight reads it. The kernel interprets them
 * (particles/simulate.slang); an encoded module draws the same random values, with the same salts, as
 * {@link CpuSimulation}.
 *
 * <p>An emitter's program is a header word (initializers | updaters << 8 | PROGRAM_* << 16), then each module as an
 * opcode word (op | words << 8), its salt, and its fields in alphabetical order of their names: a float binding in
 * {@code BINDING_WORDS} words (kind | parameter offset << 8, scale, the constant times its scale or a range's least
 * value or a curve, a range's greatest value), a color binding in {@code COLOR_WORDS} (kind | parameter offset << 8,
 * the color or a gradient), a curve or gradient in one word. Curves are offsets in the effect's block: a header (keys,
 * stride << 8, CURVE_* << 16), the times, values, slopes and a word per key of interpolation. A curve of one key folds
 * to a constant where a binding reads it.
 */
@AllowAllocation("encoding an effect when it first plays")
final class ModulePrograms implements AutoCloseable {

    /** An effect's encoded programs and curves. */
    static final class Block {
        final EffectData data;
        final int words;
        final int wordLength;
        final int curves;
        final int curveLength;
        /** Per emitter: its program's first word in the program buffer, -1 for an emitter not on the GPU. */
        final int[] programs;
        /** Per emitter: whether it keeps attributes from birth for those over life. */
        final boolean[] over;
        /** Per emitter: its renderers' records' first words in the program buffer, in the order listed. */
        final int[][] renderers;
        /** Per emitter and renderer: its texture's asset, or null. */
        final AssetRef<?>[][] textures;
        /** Per emitter: the past positions its trails keep per particle, 0 for none. */
        final int[] trails;
        /** Per emitter and renderer: whether it draws billboards back to front. */
        final boolean[][] sorted;
        int users;
        long releaseAt = -1;

        Block(EffectData data, int words, int wordLength, int curves, int curveLength, int[] programs,
            boolean[] over, int[][] renderers, AssetRef<?>[][] textures, int[] trails, boolean[][] sorted) {
            this.data = data;
            this.words = words;
            this.wordLength = wordLength;
            this.curves = curves;
            this.curveLength = curveLength;
            this.programs = programs;
            this.over = over;
            this.renderers = renderers;
            this.textures = textures;
            this.trails = trails;
            this.sorted = sorted;
        }
    }

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    private final VulkanDevice device;
    private final FrameSlots slots;
    private final Retiring retired;
    private GpuBuffer words;
    private GpuBuffer curves;
    private final RangeAllocator wordRanges;
    private final RangeAllocator curveRanges;
    private final Map<EffectData, Block> blocks = new HashMap<>();
    private Block[] releasing = new Block[8];
    private int releasingCount;

    /** Sampled slots of assets modules name (vector fields), as they are when an effect is encoded. */
    private java.util.function.ToIntFunction<AssetRef<?>> assetSlots = asset -> ParticleConstants.NO_TEXTURE;

    /** Resolves the assets modules name to sampled slots with {@code slots} from now on. */
    void assetSlots(java.util.function.ToIntFunction<AssetRef<?>> slots) {
        assetSlots = slots;
    }

    /** Whether collision chains may take ray queries: the device traces rays and the frame builds their structures. */
    private boolean rays;

    ModulePrograms(VulkanDevice device, FrameSlots slots) {
        this.device = device;
        this.slots = slots;
        this.retired = new Retiring(slots);
        wordRanges = new RangeAllocator(4096);
        curveRanges = new RangeAllocator(4096);
        words = GpuBuffer.hostVisible(device, 4L * wordRanges.capacity(), USAGE);
        try {
            curves = GpuBuffer.hostVisible(device, 4L * curveRanges.capacity(), USAGE);
        } catch (RuntimeException e) {
            words.close();
            throw e;
        }
    }

    /** The program words' address: valid for frames recorded from now on (it moves as it grows). */
    long wordsAddress() {
        return words.deviceAddress();
    }

    /** The curve keys' address, likewise. */
    long curvesAddress() {
        return curves.deviceAddress();
    }

    /** {@code data}'s block, encoded if it is not yet; taken until {@link #release}d. */
    Block acquire(EffectData data) {
        Block block = blocks.get(data);
        if (block == null) {
            block = encode(data);
            blocks.put(data, block);
        }
        block.users++;
        block.releaseAt = -1;
        return block;
    }

    /** Lets go of {@code block}: freed once no instance takes it and no frame in flight reads it. */
    void release(Block block) {
        if (--block.users > 0) {
            return;
        }
        block.releaseAt = slots.retireAt();
        if (releasingCount == releasing.length) {
            releasing = Arrays.copyOf(releasing, 2 * releasingCount);
        }
        releasing[releasingCount++] = block;
    }

    /** Frees blocks let go of that no frame reads any more, and buffers replaced by bigger ones. */
    void collect() {
        retired.release();
        int kept = 0;
        for (int i = 0; i < releasingCount; i++) {
            Block block = releasing[i];
            if (block.users > 0) {
                continue; // taken again
            }
            if (slots.isFinished(block.releaseAt)) {
                blocks.remove(block.data);
                wordRanges.release(block.words, block.wordLength);
                curveRanges.release(block.curves, block.curveLength);
            } else {
                releasing[kept++] = block;
            }
        }
        Arrays.fill(releasing, kept, releasingCount, null);
        releasingCount = kept;
    }

    /** How many effects are encoded. */
    int size() {
        return blocks.size();
    }

    @Override
    public void close() {
        words.close();
        curves.close();
        retired.close();
    }

    // Encoding.

    private Block encode(EffectData data) {
        IntList program = new IntList();
        Curves keys = new Curves();
        List<EmitterData> emitters = data.emitters();
        int[] starts = new int[emitters.size()];
        boolean[] over = new boolean[emitters.size()];
        int[][] renderers = new int[emitters.size()][];
        int[] trails = new int[emitters.size()];
        AssetRef<?>[][] textures = new AssetRef<?>[emitters.size()][];
        boolean[][] sorted = new boolean[emitters.size()][];
        for (int e = 0; e < emitters.size(); e++) {
            EmitterData emitter = emitters.get(e);
            List<Renderer> drawn = emitter.renderers();
            renderers[e] = new int[drawn.size()];
            textures[e] = new AssetRef<?>[drawn.size()];
            sorted[e] = new boolean[drawn.size()];
            for (int r = 0; r < drawn.size(); r++) {
                renderers[e][r] = renderer(drawn.get(r), program);
                textures[e][r] = drawn.get(r).settings().get("texture") instanceof AssetRef<?> t ? t : null;
                sorted[e][r] = drawn.get(r).sorted() && drawn.get(r).kind() == Renderer.Kind.BILLBOARD;
            }
            if (emitter.target() != Target.GPU) {
                starts[e] = -1;
                continue;
            }
            starts[e] = program.size;
            int header = program.add(0);
            boolean overLife = false;
            for (Module module : emitter.update()) {
                overLife |= module.type().equals(Over.SIZE) || module.type().equals(Over.COLOR);
            }
            over[e] = overLife;
            int inits = encodeOps(data, emitter.init(), 0, program, keys, assetSlots);
            int updates = encodeOps(data, emitter.update(), CpuSimulation.UPDATE_SALTS, program, keys, assetSlots);
            int points = 0;
            for (Renderer r : emitter.renderers()) {
                points = Math.max(points, trailPoints(r));
            }
            trails[e] = points;
            program.values[header] = inits | updates << 8
                | ((overLife ? PROGRAM_OVER : 0) | (points > 0 ? PROGRAM_TRAIL : 0)) << 16 | points << 24;
        }
        int wordStart = (int) take(wordRanges, Math.max(1, program.size), true);
        int curveStart = (int) take(curveRanges, Math.max(1, keys.floats.size), false);
        MemorySegment w = words.memory();
        for (int i = 0; i < program.size; i++) {
            w.setAtIndex(ValueLayout.JAVA_INT, wordStart + i, program.values[i]);
        }
        MemorySegment c = curves.memory();
        for (int i = 0; i < keys.floats.size; i++) {
            c.setAtIndex(ValueLayout.JAVA_INT, curveStart + i, keys.floats.values[i]);
        }
        for (int e = 0; e < starts.length; e++) {
            if (starts[e] >= 0) {
                starts[e] += wordStart;
            }
            for (int r = 0; r < renderers[e].length; r++) {
                renderers[e][r] += wordStart;
            }
        }
        return new Block(data, wordStart, Math.max(1, program.size), curveStart, Math.max(1, keys.floats.size), starts,
            over, renderers, textures, trails, sorted);
    }

    /** A range of {@code length} units of the words (or curves), the buffer grown if there is no room. */
    private long take(RangeAllocator ranges, long length, boolean program) {
        long start = ranges.allocate(length, 1);
        if (start >= 0) {
            return start;
        }
        long capacity = Math.max(2 * ranges.capacity(), ranges.capacity() + length);
        GpuBuffer old = program ? words : curves;
        GpuBuffer grown = GpuBuffer.hostVisible(device, 4 * capacity, USAGE);
        MemorySegment.copy(old.memory(), 0, grown.memory(), 0, old.size());
        retired.add(old); // frames in flight read it
        if (program) {
            words = grown;
        } else {
            curves = grown;
        }
        ranges.grow(capacity);
        return ranges.allocate(length, 1);
    }

    /**
     * A renderer's record ({@code RENDERER_WORDS} words, particle.slang's {@code ParticleRenderer}, at the offsets its
     * generated layout gives); returns its first word. Settings by name: {@code stretch} (velocity facing: extra length
     * per m/s, in sizes), {@code soft} (metres of fade into the opaque scene), {@code volume} (1: that fade by the view
     * ray's path through a sphere), {@code flipbook.columns} and {@code flipbook.rows} (frames in the texture),
     * {@code flipbook.frames} (frames played; all by default), {@code flipbook.fps} (frames per second of age,
     * looping; 0 by default: the frames over the particle's life), {@code flipbook.blend} (0: frames not blended);
     * ribbons: {@code ribbons} (strands, particle i in strand i % ribbons; 1 by default), {@code stride} (every
     * stride-th particle a point), {@code lodDistance} (the stride doubles each time the distance doubles past it);
     * ribbons, trails and beams: {@code width.tail} and {@code width.head} (the width at either end, in sizes; 1),
     * {@code tile} (particles, points or metres per texture repeat; 0 by default: stretched over the strip); trails:
     * {@code points}; beams: {@code segments} (16), {@code noise} (metres sideways), {@code width} (metres, 0.1),
     * {@code length} (metres along the emitter's +Y without an end given, 1), {@code color.r}, {@code color.g},
     * {@code color.b}, {@code color.a} (white).
     */
    private static int renderer(Renderer r, IntList program) {
        int columns = Math.max(1, (int) setting(r, "flipbook.columns", 1));
        int rows = Math.max(1, (int) setting(r, "flipbook.rows", 1));
        float frames = Math.max(1, Math.min(columns * rows, setting(r, "flipbook.frames", columns * rows)));
        float rate = setting(r, "flipbook.fps", 0);
        int flags = (setting(r, "volume", 0) != 0 ? DRAW_VOLUME : 0)
            | (frames > 1 && setting(r, "flipbook.blend", 1) != 0 ? DRAW_BLEND_FRAMES : 0)
            | (rate > 0 ? DRAW_LOOP : 0);
        int at = program.size;
        for (int i = 0; i < RENDERER_WORDS; i++) {
            program.add(0);
        }
        int[] w = program.values;
        w[at + ParticleRendererLayout.KIND / 4] = r.kind().ordinal();
        w[at + ParticleRendererLayout.FACING / 4] = r.facing() == null ? 0 : r.facing().ordinal();
        w[at + ParticleRendererLayout.FLAGS / 4] = flags;
        put(w, at, ParticleRendererLayout.ALPHA_TO_ADD, r.alphaToAdd());
        put(w, at, ParticleRendererLayout.STRETCH, setting(r, "stretch", 0));
        put(w, at, ParticleRendererLayout.SOFT, setting(r, "soft", 0));
        w[at + ParticleRendererLayout.COLUMNS / 4] = columns;
        w[at + ParticleRendererLayout.ROWS / 4] = rows;
        put(w, at, ParticleRendererLayout.FRAMES, frames);
        put(w, at, ParticleRendererLayout.RATE, rate);
        w[at + ParticleRendererLayout.RIBBONS / 4] = Math.max(1, (int) setting(r, "ribbons", 1));
        w[at + ParticleRendererLayout.STRIDE / 4] = Math.max(1, (int) setting(r, "stride", 1));
        put(w, at, ParticleRendererLayout.LOD_DISTANCE, setting(r, "lodDistance", 0));
        put(w, at, ParticleRendererLayout.WIDTH_TAIL, setting(r, "width.tail", 1));
        put(w, at, ParticleRendererLayout.WIDTH_HEAD, setting(r, "width.head", 1));
        put(w, at, ParticleRendererLayout.TILE, setting(r, "tile", 0));
        w[at + ParticleRendererLayout.POINTS / 4] = trailPoints(r);
        w[at + ParticleRendererLayout.SEGMENTS / 4] = Math.max(1, (int) setting(r, "segments", 16));
        put(w, at, ParticleRendererLayout.NOISE, setting(r, "noise", 0));
        put(w, at, ParticleRendererLayout.WIDTH, setting(r, "width", 0.1f));
        put(w, at, ParticleRendererLayout.LENGTH, setting(r, "length", 1));
        put(w, at, ParticleRendererLayout.COLOR, setting(r, "color.r", 1));
        put(w, at, ParticleRendererLayout.COLOR + 4, setting(r, "color.g", 1));
        put(w, at, ParticleRendererLayout.COLOR + 8, setting(r, "color.b", 1));
        put(w, at, ParticleRendererLayout.COLOR + 12, setting(r, "color.a", 1));
        assert ParticleRendererLayout.BYTES == 4 * RENDERER_WORDS;
        return at;
    }

    private static void put(int[] words, int at, int offset, float value) {
        words[at + offset / 4] = Float.floatToRawIntBits(value);
    }

    /** The past positions a trail renderer keeps per particle; 0 for other renderers. */
    static int trailPoints(Renderer r) {
        return r.kind() == Renderer.Kind.TRAIL ? Math.max(2, (int) setting(r, "points", 2)) : 0;
    }

    /** The strands of a ribbon renderer; 1 for other renderers. */
    static int ribbons(Renderer r) {
        return r.kind() == Renderer.Kind.RIBBON ? Math.max(1, (int) setting(r, "ribbons", 1)) : 1;
    }

    static float setting(Renderer r, String name, float fallback) {
        return r.settings().get(name) instanceof Number n ? n.floatValue() : fallback;
    }

    /**
     * Encodes {@code modules}, each with its salt by its place in the list; of each collision chain only the first
     * method the GPU has (ray queries where the device traces rays). Returns how many ops it encoded.
     */
    private int encodeOps(EffectData data, List<Module> modules, int salts, IntList program, Curves keys,
        java.util.function.ToIntFunction<AssetRef<?>> assetSlots) {
        int encoded = 0;
        boolean chosen = false;
        for (int m = 0; m < modules.size(); m++) {
            Module module = modules.get(m);
            ModuleType type = ModuleRegistry.BUILT_IN.type(module.type());
            if (module.type().startsWith("collide.")) {
                chosen &= module.value("fallback") instanceof Binding b && b.kind() == Binding.Kind.CONSTANT
                    && b.value() * b.scale() >= 0.5f; // a method that is no fallback starts a chain
                boolean here = type.runsOn(Target.GPU) && (type.capability() == Capability.NONE || rays);
                if (chosen || !here) {
                    continue;
                }
                chosen = true;
            }
            encoded++;
            int op = opcode(module.type());
            int at = program.add(0);
            program.add(salts + 16 * m);
            List<String> names = type.fields().keySet().stream().sorted().toList();
            for (String name : names) {
                Object value = module.value(name);
                if (value == null) {
                    value = type.fields().get(name).fallback();
                }
                switch (value) {
                    case Binding b -> binding(data, b, program, keys);
                    case ColorBinding c -> color(data, c, program, keys);
                    case FloatCurve c -> program.add(keys.curve(c));
                    case AssetRef<?> asset -> { // its sampled slot, padded to a binding's words
                        program.add(assetSlots.applyAsInt(asset));
                        for (int w = 1; w < BINDING_WORDS; w++) {
                            program.add(0);
                        }
                    }
                    default -> program.add(keys.gradient((ColorGradient) value));
                }
            }
            program.values[at] = op | (program.size - at) << 8;
        }
        return encoded;
    }

    private static int opcode(String type) {
        return switch (type) {
            case Shape.POINT -> OP_POINT;
            case Shape.SPHERE -> OP_SPHERE;
            case Shape.BOX -> OP_BOX;
            case Shape.CONE -> OP_CONE;
            case Shape.DISC -> OP_DISC;
            case Init.LIFETIME -> OP_LIFETIME;
            case Init.SPEED -> OP_SPEED;
            case Init.VELOCITY -> OP_VELOCITY;
            case Init.SIZE -> OP_SIZE;
            case Init.COLOR -> OP_COLOR;
            case Forces.GRAVITY -> OP_GRAVITY;
            case Forces.DRAG -> OP_DRAG;
            case Over.SIZE -> OP_OVER_SIZE;
            case Over.COLOR -> OP_OVER_COLOR;
            case Forces.WIND -> ParticleConstants.OP_WIND;
            case Forces.CURL -> ParticleConstants.OP_CURL;
            case Forces.VORTEX -> ParticleConstants.OP_VORTEX;
            case Forces.POINT -> ParticleConstants.OP_POINT_FORCE;
            case Forces.LINE -> ParticleConstants.OP_LINE;
            case Forces.ORBIT -> ParticleConstants.OP_ORBIT;
            case Forces.FIELD -> ParticleConstants.OP_FIELD;
            case Forces.SPEED_LIMIT -> ParticleConstants.OP_SPEED_LIMIT;
            case Collide.COLLIDERS -> ParticleConstants.OP_COLLIDERS;
            case Collide.HEIGHT -> ParticleConstants.OP_HEIGHT;
            case Collide.DEPTH -> ParticleConstants.OP_DEPTH;
            default -> throw new IllegalArgumentException("No GPU module " + type + " (plugin modules come later)");
        };
    }

    /** A float binding in {@code BINDING_WORDS} words. */
    private static void binding(EffectData data, Binding b, IntList program, Curves keys) {
        int start = program.size;
        Binding.Kind kind = b.kind();
        FloatCurve curve = b.curve();
        if (curve != null && curve.size() == 1 && kind != Binding.Kind.PARAMETER_CURVE) {
            // one key: the same value at every time
            program.add(Binding.Kind.CONSTANT.ordinal());
            program.add(Float.floatToRawIntBits(1));
            program.add(Float.floatToRawIntBits(curve.value(0) * b.scale()));
            program.add(0);
            return;
        }
        int parameter = b.parameter() == null ? 0 : data.parameterOffset(b.parameter());
        program.add(kind.ordinal() | parameter << 8);
        program.add(Float.floatToRawIntBits(b.scale()));
        switch (kind) {
            case CONSTANT -> {
                program.add(Float.floatToRawIntBits(b.value() * b.scale()));
                program.add(0);
            }
            case PARAMETER -> {
                program.add(0);
                program.add(0);
            }
            case RANDOM -> {
                program.add(Float.floatToRawIntBits(b.range().min()));
                program.add(Float.floatToRawIntBits(b.range().max()));
            }
            default -> {
                program.add(keys.curve(curve));
                program.add(0);
            }
        }
        assert program.size - start == BINDING_WORDS;
    }

    /** A color binding in {@code COLOR_WORDS} words. */
    private static void color(EffectData data, ColorBinding c, IntList program, Curves keys) {
        int start = program.size;
        int parameter = c.parameter() == null ? 0 : data.parameterOffset(c.parameter());
        program.add(c.kind().ordinal() | parameter << 8);
        switch (c.kind()) {
            case CONSTANT -> {
                program.add(Float.floatToRawIntBits(c.r()));
                program.add(Float.floatToRawIntBits(c.g()));
                program.add(Float.floatToRawIntBits(c.b()));
                program.add(Float.floatToRawIntBits(c.a()));
            }
            case PARAMETER -> {
                for (int i = 0; i < 4; i++) {
                    program.add(0);
                }
            }
            default -> {
                program.add(keys.gradient(c.gradient()));
                for (int i = 0; i < 3; i++) {
                    program.add(0);
                }
            }
        }
        assert program.size - start == COLOR_WORDS;
    }

    /** An effect's curve keys as floats (by their bits), each curve once. */
    private static final class Curves {
        final IntList floats = new IntList();
        final Map<Object, Integer> offsets = new HashMap<>();

        int curve(FloatCurve curve) {
            Integer known = offsets.get(curve);
            if (known != null) {
                return known;
            }
            int n = curve.size();
            int at = header(n, 1, n > 4 && even(curve::time, n));
            for (int i = 0; i < n; i++) {
                floats.add(Float.floatToRawIntBits(curve.time(i)));
            }
            for (int i = 0; i < n; i++) {
                floats.add(Float.floatToRawIntBits(curve.value(i)));
            }
            for (int i = 0; i < n; i++) {
                floats.add(Float.floatToRawIntBits(curve.slope(i)));
            }
            for (int i = 0; i < n; i++) {
                floats.add(curve.interpolation(i).ordinal());
            }
            offsets.put(curve, at);
            return at;
        }

        int gradient(ColorGradient gradient) {
            Integer known = offsets.get(gradient);
            if (known != null) {
                return known;
            }
            int n = gradient.size();
            int at = header(n, 4, n > 4 && even(gradient::time, n));
            for (int i = 0; i < n; i++) {
                floats.add(Float.floatToRawIntBits(gradient.time(i)));
            }
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < 4; c++) {
                    floats.add(Float.floatToRawIntBits(gradient.color(i, c)));
                }
            }
            float[] slopes = slopes(gradient);
            for (float slope : slopes) {
                floats.add(Float.floatToRawIntBits(slope));
            }
            for (int i = 0; i < n; i++) {
                floats.add(gradient.interpolation(i).ordinal());
            }
            offsets.put(gradient, at);
            return at;
        }

        private int header(int keys, int stride, boolean even) {
            return floats.add(keys | stride << 8 | (even ? CURVE_EVEN : 0) << 16);
        }

        /** Whether the times are evenly spaced: the kernel then finds a segment by division. */
        private static boolean even(java.util.function.IntToDoubleFunction time, int n) {
            double first = time.applyAsDouble(0);
            double h = (time.applyAsDouble(n - 1) - first) / (n - 1);
            for (int i = 1; i < n - 1; i++) {
                if (Math.abs(time.applyAsDouble(i) - (first + i * h)) > 1e-6 * Math.abs(h)) {
                    return false;
                }
            }
            return true;
        }

        /** A gradient's slopes per key and component, as ColorGradient computes them (Catmull-Rom). */
        private static float[] slopes(ColorGradient g) {
            int n = g.size();
            float[] slopes = new float[4 * n];
            for (int i = 0; i < n && n > 1; i++) {
                int before = Math.max(0, i - 1);
                int after = Math.min(n - 1, i + 1);
                for (int c = 0; c < 4; c++) {
                    slopes[4 * i + c] = (g.color(after, c) - g.color(before, c)) / (g.time(after) - g.time(before));
                }
            }
            return slopes;
        }
    }

    /** A growing list of ints. */
    private static final class IntList {
        int[] values = new int[64];
        int size;

        int add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, 2 * size);
            }
            values[size] = value;
            return size++;
        }
    }
}
