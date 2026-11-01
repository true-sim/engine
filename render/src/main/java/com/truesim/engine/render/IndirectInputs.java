package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.ViewPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a view's indirect light is made of, gathered while the graph builds, as {@link ShadowInputs} gathers shadows: the
 * table ({@link Keys#INDIRECT_LIGHT}) and the images and buffers its sections name (an environment's cubes, later probe
 * atlases), which shading samples. A shared request: whatever fills a section adds to the same set, and every shading
 * pass reads all of it, so a new indirect technique needs no change to shading. Each {@link ViewPort} has a set of its
 * own.
 *
 * <pre>{@code
 * IndirectInputs.Set indirect = IndirectInputs.of(g);  // null without indirect light: the constant sky
 * if (indirect != null) indirect.readBy(pass, Access.SAMPLED_FRAGMENT, BufferAccess.DRAW_READ);
 * }</pre>
 *
 * @param view the view whose shading reads the set ({@link GraphBuilder#view()}); null: the frame's
 */
public record IndirectInputs(ViewPort view) implements Request<IndirectInputs.Set> {

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Set build(GraphBuilder g) {
        return new Set();
    }

    /**
     * The view's indirect light as shading reads it: the set with its table, where a technique provides
     * {@link Keys#INDIRECT_LIGHT}; else null.
     */
    @AllowAllocation("building the graph, when it changes")
    public static Set of(GraphBuilder g) {
        Buffer table = g.optional(Keys.INDIRECT_LIGHT);
        return table == null ? null : g.request(new IndirectInputs(g.view())).table(table);
    }

    /** The indirect inputs of one build. */
    @AllowAllocation("building the graph, when it changes")
    public static final class Set {
        private final List<Image> images = new ArrayList<>();
        private final List<Buffer> buffers = new ArrayList<>();
        private Buffer table;

        /** The table shading reads: the latest of {@link Keys#INDIRECT_LIGHT}. */
        Set table(Buffer table) {
            this.table = Objects.requireNonNull(table, "table");
            return this;
        }

        /** The table, as of the last {@link IndirectInputs#of}. */
        public Buffer table() {
            return table;
        }

        /** Shading samples {@code image}. */
        public Set sample(Image image) {
            images.add(Objects.requireNonNull(image, "image"));
            return this;
        }

        /** Shading reads {@code buffer}. */
        public Set read(Buffer buffer) {
            buffers.add(Objects.requireNonNull(buffer, "buffer"));
            return this;
        }

        /** Declares that {@code pass} reads the table and buffers with {@code read}, samples the images with {@code access}. */
        public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass, Access access, BufferAccess read) {
            pass.read(table, read);
            for (Image image : images) {
                pass.read(image, access);
            }
            for (Buffer buffer : buffers) {
                pass.read(buffer, read);
            }
            return pass;
        }
    }
}
