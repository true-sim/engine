package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.HooksConstants;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.Specialization;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A view's shading hooks as one build gathers them, as {@link IndirectInputs} gathers its indirect light: the hooks
 * techniques add ({@link ShadingHooks#add}, each with its {@link ShadingHookSource}) and those the scene holds values
 * for ({@link ShadingHookValues}), the images and buffers they read, and the render views whose shading runs them. A
 * shared request: every shading pass of the view reads all of it ({@link #of}, {@link Set#readBy}), and makes its
 * pipelines with the hooks it compiles ({@link Set#compiled}); {@link ViewShadingHooks} writes the view's block each
 * frame. Each {@link ViewPort} has a set of its own.
 *
 * <pre>{@code
 * ShadingHookInputs.Set hooks = ShadingHookInputs.of(g, sceneView.view());
 * Pipeline shade = g.computeProgram(shader, entry, push, hooks.specialization(features));
 * hooks.readBy(pass, Access.SAMPLED_COMPUTE, BufferAccess.STORAGE_READ);
 * }</pre>
 *
 * @param view the view whose shading reads the set ({@link GraphBuilder#view()}); null: the frame's
 */
public record ShadingHookInputs(ViewPort view) implements Request<ShadingHookInputs.Set> {

    /** Which hooks a view's pipelines are made with: those the view runs, or those any view runs. */
    public enum Specialize {
        /**
         * Each view's pipelines run the hooks that view has: the least code per pipeline; views with different hooks
         * make pipelines of their own.
         */
        VIEW,
        /**
         * Every view's pipelines run every hook a view of the device has had: one set of pipelines for all views; a
         * view skips hooks it does not run by their bits (a branch the same for the whole draw).
         */
        UNION
    }

    /** Which hooks a view's pipelines are made with ({@link Specialize}). */
    public static final Setting<Specialize> SPECIALIZE = Setting.choice("render.shadingHooks.specialize",
        Specialize.VIEW, "Shading hooks compiled into a view's pipelines: VIEW, the hooks it runs; UNION, every hook "
            + "any view has run (one set of pipelines; the others skipped by a branch)");

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Set build(GraphBuilder g) {
        return new Set(g.setting(SPECIALIZE));
    }

    /**
     * The view's hooks, as shading reads them: after every technique adding one (they modify {@link Keys#SHADING_HOOKS},
     * which this requires), with {@code shaded} among the render views its block is written for.
     */
    @AllowAllocation("building the graph, when it changes")
    public static Set of(GraphBuilder g, RenderView shaded) {
        Buffer token = g.require(Keys.SHADING_HOOKS);
        return g.request(new ShadingHookInputs(g.view())).token(g, token).view(shaded);
    }

    /** The shading hooks of one build of a view. */
    @AllowAllocation("building the graph, when it changes")
    public static final class Set {
        private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED;

        private final Specialize specialize;
        private final List<Image> images = new ArrayList<>();
        private final List<Buffer> buffers = new ArrayList<>();
        private final List<RenderView> views = new ArrayList<>();
        private final List<ProbeHooks> probes = new ArrayList<>();
        private ProbeHooks[] probeArray = new ProbeHooks[0];
        private ShadingHookSource[] sources = new ShadingHookSource[0]; // by hook index, or null
        private ShadingHooks.Set added = ShadingHooks.Set.NONE;
        private ShadingHookValues values;
        private ShadingHooks.Set compiled;
        private Buffer token;
        private RenderView[] written = new RenderView[0];

        Set(Specialize specialize) {
            this.specialize = specialize;
        }

        /**
         * The view's token, as shading requires it: every hook added. The first time, declares the pass writing the
         * view's blocks each frame, reading what the hooks' sources take the addresses and slots of. Recorded with
         * the frame's other passes, before the frame is submitted: the blocks are host-visible, the GPU reads them
         * after.
         */
        Set token(GraphBuilder g, Buffer token) {
            if (this.token == null) {
                this.token = Objects.requireNonNull(token, "token");
                GraphBuilder.PassBuilder pass = g.pass("shading hooks");
                for (Image image : images) {
                    pass.read(image, Access.SAMPLED_COMPUTE);
                }
                for (Buffer buffer : buffers) {
                    pass.read(buffer, BufferAccess.STORAGE_READ);
                }
                for (ProbeHooks probe : probes) {
                    pass.read(probe.data(), BufferAccess.STORAGE_READ);
                }
                probeArray = probes.toArray(new ProbeHooks[0]);
                pass.sideEffects().record(this::publish);
            }
            return this;
        }

        /** A probe volume's own block, written with the view's (its rays' hits). */
        Set probe(ProbeHooks probe) {
            if (token != null) {
                throw new IllegalStateException("A probe volume was built after the view's shading");
            }
            probes.add(probe);
            return this;
        }

        /** Hook {@code index} runs in the view, its parameters from {@code source}. */
        Set add(int index, ShadingHookSource source) {
            if (compiled != null) {
                throw new IllegalStateException("Shading hook " + index + " was added after the view's shading made "
                    + "its pipelines: the technique adding it must modify Keys.SHADING_HOOKS");
            }
            if (index >= sources.length) {
                sources = java.util.Arrays.copyOf(sources, index + 1);
            }
            if (sources[index] != null) {
                throw new IllegalStateException("Two techniques of a view add shading hook " + index + ": a hook has "
                    + "one writer per view");
            }
            sources[index] = Objects.requireNonNull(source, "source");
            added = added.with(index);
            return this;
        }

        /** Shading reads {@code buffer} for a hook (its parameters name it). */
        public Set read(Buffer buffer) {
            buffers.add(Objects.requireNonNull(buffer, "buffer"));
            return this;
        }

        /** Shading samples {@code image} for a hook. */
        public Set sample(Image image) {
            images.add(Objects.requireNonNull(image, "image"));
            return this;
        }

        /**
         * {@code shaded}'s block is written for the view (its View points shaders at it): its records, as of this build,
         * at their defaults until the hooks write theirs.
         */
        public Set view(RenderView shaded) {
            Objects.requireNonNull(shaded, "shaded");
            if (!views.contains(shaded)) {
                views.add(shaded);
                written = views.toArray(new RenderView[0]);
                MemorySegment defaults = ShadingHookValues.of(shaded.renderer().device()).hooks().defaultBlock();
                long header = HooksConstants.SHADING_HOOK_HEADER;
                shaded.hooks().staging().asSlice(header).copyFrom(defaults.asSlice(header));
            }
            if (values == null) {
                values = ShadingHookValues.of(shaded.renderer().device());
            }
            return this;
        }

        /** The listing the hooks are of. */
        public ShadingHooks listing() {
            return values().hooks();
        }

        private ShadingHookValues values() {
            if (values == null) {
                throw new IllegalStateException("No render view shades with the view's shading hooks");
            }
            return values;
        }

        /**
         * The hooks the view's pipelines are made with ({@link #SPECIALIZE}): those techniques added and the scene
         * holds values for, and with {@link Specialize#UNION} those of every view of the device so far (a view adding
         * one rebuilds the graph a frame later, every view then making pipelines with it).
         */
        public ShadingHooks.Set compiled() {
            if (compiled == null) {
                ShadingHookValues v = values();
                ShadingHooks.Set runs = added.union(v.present());
                compiled = specialize == Specialize.UNION ? v.union(runs) : runs;
            }
            return compiled;
        }

        /** A pipeline's constants: its shader's own ({@code own}, ids 0, 1, ...), and the hooks it is made with. */
        public Specialization specialization(int... own) {
            return listing().specialization(compiled(), own);
        }

        /** Declares that {@code pass} reads the hooks' data: their block, buffers and images. */
        public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass, Access access, BufferAccess read) {
            if (token != null) {
                pass.read(token, read);
            }
            for (Image image : images) {
                pass.read(image, access);
            }
            for (Buffer buffer : buffers) {
                pass.read(buffer, read);
            }
            return pass;
        }

        /** Whether a technique added hook {@code index} (else the scene's values alone run it). */
        public boolean added(int index) {
            return added.has(index);
        }

        /** How many views' blocks it writes. */
        int views() {
            return written.length;
        }

        /**
         * Writes the frame's bits and records into {@code block} (a block's bytes, its header aside): each hook a
         * technique added as its source has it, each the scene alone holds values for as they are, the others at their
         * defaults. Allocates nothing.
         */
        void compose(PassContext ctx, MemorySegment block) {
            ShadingHooks hooks = values.hooks();
            int n = hooks.count();
            long words = HooksConstants.SHADING_HOOK_HEADER;
            for (int w = 0; w < (n + 31) / 32; w++) {
                block.set(U32, words + 4L * w, 0);
            }
            ShadingHooks.Set runs = compiled();
            for (int i = 0; i < n; i++) {
                ShadingHookSource source = i < sources.length ? sources[i] : null;
                boolean on;
                if (source != null) {
                    on = source.active();
                    ShadingHookParameters authored = values.authored(i);
                    source.write(ctx, authored != null ? authored : hooks.defaults(i), block, hooks.recordOffset(i));
                } else if (values.authored(i) != null && runs.has(i)) {
                    on = values.on(i);
                    values.authored(i).write(block, hooks.recordOffset(i));
                } else {
                    continue;
                }
                if (on) {
                    long at = ShadingHooks.activeWord(i);
                    block.set(U32, at, block.get(U32, at) | 1 << (i % 32));
                }
            }
        }

        /** The view's blocks for the frame recording in {@code ctx}: composed, handed over, pointed at. */
        void publish(PassContext ctx) {
            if (values == null) {
                return;
            }
            for (RenderView view : written) {
                ShadingHookBlock block = view.hooks();
                compose(ctx, block.staging());
                block.publish();
                view.writeHooksAddress();
            }
            for (ProbeHooks probe : probeArray) {
                probe.publish(ctx, this);
            }
        }
    }
}
