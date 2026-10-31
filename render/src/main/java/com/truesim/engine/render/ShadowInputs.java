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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What the frame's shadow techniques made for shading, gathered while the graph builds: the images shading samples
 * (shadow maps, masks), buffers it must wait for (a scene's top level), and setups that fill their lights' shadow table
 * entries when the frame is recorded (with the images' slots). A shared request: every shadow technique adds to the
 * same set, and every shading technique reads all of it, so a new shadow technique needs no change to shading. Each
 * {@link ViewPort} has a set of its own, as it has a light list: its shadow techniques add to its set.
 *
 * <p>Shadow techniques {@link com.truesim.engine.graph.Technique#modifies() modify} {@link Keys#LIGHT_LIST}: they are
 * built whenever shading requires the light list, before shading adds its passes.
 *
 * @param view the view whose shading reads the set ({@link GraphBuilder#view()}); null: the frame's
 */
public record ShadowInputs(ViewPort view) implements Request<ShadowInputs.Set> {

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Set build(GraphBuilder g) {
        return new Set();
    }

    /** The shadow inputs of one build. */
    @AllowAllocation("building the graph, when it changes")
    public static final class Set {
        private final List<Image> images = new ArrayList<>();
        private final List<Buffer> buffers = new ArrayList<>();
        private final List<Consumer<PassContext>> setups = new ArrayList<>();
        private Buffer topLevel; // what inline ray shadows trace, or null

        /** Shading samples {@code image}. */
        public Set sample(Image image) {
            images.add(image);
            return this;
        }

        /** Shading runs after whatever writes {@code buffer}. */
        public Set after(Buffer buffer) {
            buffers.add(buffer);
            return this;
        }

        /** Run when shading records, first: e.g. a shadow table entry with an image's slot. Must not allocate. */
        public Set setup(Consumer<PassContext> setup) {
            setups.add(setup);
            return this;
        }

        /**
         * Some light casts inline ray shadows: shading that can traces them through {@code topLevel} (a {@code TopLevel}
         * request's structure), which it reads when it does ({@link #readBy(GraphBuilder.PassBuilder, Access,
         * BufferAccess, boolean)}).
         */
        public Set rays(Buffer topLevel) {
            this.topLevel = Objects.requireNonNull(topLevel, "topLevel");
            return this;
        }

        public boolean traced() {
            return topLevel != null;
        }

        /** Declares that {@code pass} samples the images with {@code access}, reads the buffers with {@code read}. */
        public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass, Access access, BufferAccess read) {
            return readBy(pass, access, read, false);
        }

        /**
         * Like {@link #readBy(GraphBuilder.PassBuilder, Access, BufferAccess)}, for a compute pass that, with
         * {@code traces}, traces the inline ray shadows: it reads the top level as an acceleration structure too.
         */
        public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass, Access access, BufferAccess read,
            boolean traces) {
            if (traces && topLevel != null) {
                pass.read(topLevel, BufferAccess.ACCELERATION_STRUCTURE_READ);
            }
            for (Image image : images) {
                pass.read(image, access);
            }
            for (Buffer buffer : buffers) {
                pass.read(buffer, read);
            }
            return pass;
        }

        /** Runs the setups: from the shading pass, when it records. Allocates nothing. */
        public void prepare(PassContext ctx) {
            for (int i = 0; i < setups.size(); i++) {
                setups.get(i).accept(ctx);
            }
        }
    }
}
