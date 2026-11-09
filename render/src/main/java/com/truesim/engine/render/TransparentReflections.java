package com.truesim.engine.render;

import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.render.blocks.DepthCopy;
import com.truesim.engine.render.blocks.DepthPyramid;
import com.truesim.engine.render.layout.ClassicPush;
import com.truesim.engine.render.layout.ForwardPush;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.graph.Technique;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * Screen-space reflections of blended and transmissive surfaces ({@link ScreenSpaceReflections#TRANSPARENT},
 * {@code render.ssr.transparent}): what a transparency technique's draw needs for each fragment to trace its own
 * mirror ray through the opaque scene, inline in its fragment shader (reflections/transparent.slang), as
 * {@link ScreenSpaceReflections}' hierarchical march does for the opaque pixels: a copy of the opaque depth
 * ({@link DepthCopy}: the draw tests the depth itself as its attachment), its nearest-depth pyramid
 * ({@link DepthPyramid} with {@code nearest}, shared with the opaque reflections' {@code HIZ}) and the opaque scene's
 * color with its mip chain ({@link SceneBehind}, the scene behind transmissive surfaces, requested whenever this is on).
 * The march's tunables are the chosen kind's ({@code render.ssr.hiz.*} or {@code render.ssr.linear.*}), read as frames
 * record; the march is always the hierarchical one.
 *
 * <p>Made by transparency techniques ({@link SortedTransparency}, {@link WeightedBlendedTransparency}) while their
 * graph builds, where the setting is on and {@code render.ssr} is one of the engine's kinds, and handed to the
 * geometry providers with their {@link BlendedTargets}. Their draws take its push words ({@link #words}): the blended
 * bin's pipelines with the fragment stage's {@code REFLECTIONS} constant set.
 *
 * @param depth the copy of the opaque depth, sampled
 * @param pyramid its nearest-depth pyramid, sampled
 * @param kind the chosen kind, whose tunables the march takes
 * @param settings where the tunables are read as frames record
 */
public record TransparentReflections(Image depth, Image pyramid,
    TechniqueKind<ScreenSpaceReflections.March, ScreenSpaceReflections, Technique> kind, EngineSettings settings) {

    /** The push words' bytes: from {@code reflectDepth} to the end of the forward and classic push blocks. */
    static final int BYTES = ForwardPush.BYTES - ForwardPush.REFLECT_DEPTH;

    static {
        if (ClassicPush.REFLECT_DEPTH != ForwardPush.REFLECT_DEPTH || ClassicPush.BYTES != ForwardPush.BYTES) {
            throw new AssertionError("classic/draw.slang's push block must keep forward.slang's reflection words");
        }
    }

    public TransparentReflections {
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(pyramid, "pyramid");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(settings, "settings");
    }

    /**
     * What the draws of blended surfaces over {@code depth} (the opaque depth) in the view building need to trace
     * reflections, made now; null where {@code render.ssr.transparent} is off (the graph rebuilds when it changes) or
     * {@code render.ssr} is off or not one of the engine's kinds.
     */
    @AllowAllocation("building the graph, when it changes")
    public static TransparentReflections of(GraphBuilder g, Image depth) {
        if (!g.setting(ScreenSpaceReflections.TRANSPARENT)) {
            return null;
        }
        TechniqueKind<?, ScreenSpaceReflections, Technique> chosen = g.technique(ScreenSpaceReflections.SLOT);
        TechniqueKind<ScreenSpaceReflections.March, ScreenSpaceReflections, Technique> kind =
            chosen == ScreenSpaceReflections.HIZ ? ScreenSpaceReflections.HIZ
                : chosen == ScreenSpaceReflections.LINEAR ? ScreenSpaceReflections.LINEAR : null;
        if (kind == null) {
            return null;
        }
        Image copy = g.request(new DepthCopy(depth, g.view()));
        Image pyramid = g.request(new DepthPyramid(depth, g.view(), true));
        return new TransparentReflections(copy, pyramid, kind, g.settings());
    }

    /** Declares that {@code pass}'s fragment shaders sample the depth's copy and pyramid. */
    public GraphBuilder.PassBuilder readBy(GraphBuilder.PassBuilder pass) {
        return pass.read(depth, Access.SAMPLED_FRAGMENT).read(pyramid, Access.SAMPLED_FRAGMENT);
    }

    /**
     * The push words for a pass with context {@code ctx}, on {@code stack} ({@link #BYTES}), the tunables as they are
     * now; {@link #copyWords} puts them into a push block. Allocates nothing.
     */
    public long words(PassContext ctx, MemoryStack stack) {
        ScreenSpaceReflections.March march = kind.config(settings);
        long words = stack.ncalloc(8, 1, BYTES);
        long base = -ForwardPush.REFLECT_DEPTH;
        memPutInt(words + base + ForwardPush.REFLECT_DEPTH, ctx.sampled(depth));
        memPutInt(words + base + ForwardPush.REFLECT_PYRAMID, ctx.sampled(pyramid));
        memPutInt(words + base + ForwardPush.REFLECT_PYRAMID_MIPS, pyramid.mips());
        memPutInt(words + base + ForwardPush.REFLECT_STEPS, march.maxSteps());
        memPutFloat(words + base + ForwardPush.REFLECT_THICKNESS, march.thickness());
        memPutFloat(words + base + ForwardPush.REFLECT_DISTANCE, march.maxDistance());
        memPutFloat(words + base + ForwardPush.REFLECT_CUTOFF, march.roughnessCutoff());
        memPutFloat(words + base + ForwardPush.REFLECT_EDGE_FADE, march.edgeFade());
        return words;
    }

    /** Puts the push words at {@code words} (0: none, the block left as it is) into the push block at {@code push}. */
    static void copyWords(long words, long push) {
        if (words != 0) {
            memCopy(words, push + ForwardPush.REFLECT_DEPTH, BYTES);
        }
    }
}
