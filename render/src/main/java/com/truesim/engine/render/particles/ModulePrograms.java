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
import static com.truesim.engine.render.layout.ParticleConstants.RENDERER_WORDS;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.RangeAllocator;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.effects.Binding;
import com.truesim.scene.effects.ColorBinding;
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
        int users;
        long releaseAt = -1;

        Block(EffectData data, int words, int wordLength, int curves, int curveLength, int[] programs,
            boolean[] over, int[][] renderers, AssetRef<?>[][] textures) {
            this.data = data;
            this.words = words;
            this.wordLength = wordLength;
            this.curves = curves;
            this.curveLength = curveLength;
            this.programs = programs;
            this.over = over;
            this.renderers = renderers;
            this.textures = textures;
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
        AssetRef<?>[][] textures = new AssetRef<?>[emitters.size()][];
        for (int e = 0; e < emitters.size(); e++) {
            EmitterData emitter = emitters.get(e);
            List<Renderer> drawn = emitter.renderers();
            renderers[e] = new int[drawn.size()];
            textures[e] = new AssetRef<?>[drawn.size()];
            for (int r = 0; r < drawn.size(); r++) {
                renderers[e][r] = renderer(drawn.get(r), program);
                textures[e][r] = drawn.get(r).settings().get("texture") instanceof AssetRef<?> t ? t : null;
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
            encodeOps(data, emitter.init(), 0, program, keys);
            encodeOps(data, emitter.update(), CpuSimulation.UPDATE_SALTS, program, keys);
            program.values[header] = emitter.init().size() | emitter.update().size() << 8
                | (overLife ? PROGRAM_OVER : 0) << 16;
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
            over, renderers, textures);
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
     * A renderer's record ({@code RENDERER_WORDS} words, particle.slang's {@code ParticleRenderer}); returns its first
     * word. Settings by name: {@code stretch} (velocity facing: extra length per m/s, in sizes), {@code soft} (metres of
     * fade into the opaque scene), {@code volume} (1: that fade by the view ray's path through a sphere),
     * {@code flipbook.columns} and {@code flipbook.rows} (frames in the texture), {@code flipbook.frames} (frames
     * played; all by default), {@code flipbook.fps} (frames per second of age, looping; 0 by default: the frames over
     * the particle's life) and {@code flipbook.blend} (0: frames not blended).
     */
    private static int renderer(Renderer r, IntList program) {
        int columns = Math.max(1, (int) setting(r, "flipbook.columns", 1));
        int rows = Math.max(1, (int) setting(r, "flipbook.rows", 1));
        float frames = Math.max(1, Math.min(columns * rows, setting(r, "flipbook.frames", columns * rows)));
        float rate = setting(r, "flipbook.fps", 0);
        int flags = (setting(r, "volume", 0) != 0 ? DRAW_VOLUME : 0)
            | (frames > 1 && setting(r, "flipbook.blend", 1) != 0 ? DRAW_BLEND_FRAMES : 0)
            | (rate > 0 ? DRAW_LOOP : 0);
        int at = program.add(r.kind().ordinal());
        program.add(r.facing() == null ? 0 : r.facing().ordinal());
        program.add(flags);
        program.add(Float.floatToRawIntBits(r.alphaToAdd()));
        program.add(Float.floatToRawIntBits(setting(r, "stretch", 0)));
        program.add(Float.floatToRawIntBits(setting(r, "soft", 0)));
        program.add(columns);
        program.add(rows);
        program.add(Float.floatToRawIntBits(frames));
        program.add(Float.floatToRawIntBits(rate));
        program.add(0);
        program.add(0);
        assert program.size - at == RENDERER_WORDS;
        return at;
    }

    private static float setting(Renderer r, String name, float fallback) {
        return r.settings().get(name) instanceof Number n ? n.floatValue() : fallback;
    }

    private static void encodeOps(EffectData data, List<Module> modules, int salts, IntList program, Curves keys) {
        for (int m = 0; m < modules.size(); m++) {
            Module module = modules.get(m);
            ModuleType type = ModuleRegistry.BUILT_IN.type(module.type());
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
                    default -> program.add(keys.gradient((ColorGradient) value));
                }
            }
            program.values[at] = op | (program.size - at) << 8;
        }
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
