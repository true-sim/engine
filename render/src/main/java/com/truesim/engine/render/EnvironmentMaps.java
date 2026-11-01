package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Request;
import com.truesim.engine.graph.Subresource;
import com.truesim.engine.render.layout.FilterPush;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;

/**
 * An {@link Environment} filtered for lighting, kept by the graph (persistent images) and filtered anew only when the
 * environment changes or its images are new: its radiance as a cube with a mip chain (from the panorama, or its color),
 * that cube prefiltered for GGX reflections (a level per roughness, 0 to 1) and for sheen (the Charlie distribution),
 * and its diffuse light as spherical harmonics. A shared request: views of one environment filter it once.
 * environment/filter.slang does the work, as the glTF sample viewer filters its environments.
 *
 * <pre>{@code
 * EnvironmentMaps.Maps maps = g.request(new EnvironmentMaps(environment, EnvironmentMaps.QUALITY));
 * }</pre>
 *
 * @param environment what is filtered
 * @param quality the cubes' sizes
 */
public record EnvironmentMaps(Environment environment, Quality quality) implements Request<EnvironmentMaps.Maps> {

    /** How finely an environment is filtered: the cubes' sizes, texels a side, and the samples per texel. */
    public record Quality(int radiance, int specular, int specularMips, int sheen, int samples) {

        public Quality {
            if (Integer.bitCount(radiance) != 1 || Integer.bitCount(specular) != 1 || Integer.bitCount(sheen) != 1
                || specular > radiance || specularMips < 2 || specularMips > Integer.numberOfTrailingZeros(specular) + 1
                || samples < 1) {
                throw new IllegalArgumentException("Powers of two, the specular cube no larger than the radiance's: "
                    + this);
            }
        }
    }

    /** The default: a 512 radiance cube, 256 for reflections in 6 levels of roughness, 64 for sheen, 256 samples. */
    public static final Quality QUALITY = new Quality(512, 256, 6, 64, 256);

    /** The images and buffer an environment is filtered into, for shading to read ({@link IndirectInputs}). */
    public record Maps(Image radiance, Image specular, Image sheen, Buffer sh) {
    }

    /** The cube level spherical harmonics are projected from: 32 texels a side, plenty for 9 coefficients. */
    private static final int SH_SIZE = 32;

    public EnvironmentMaps {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(quality, "quality");
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Maps build(GraphBuilder g) {
        Format format = Format.R16G16B16A16_SFLOAT;
        // The radiance cube's name holds every size: a new quality makes every image anew, all of them filtered.
        String name = "environment " + environment.name();
        Image radiance = g.persistent(name + " radiance (" + quality.radiance() + ", " + quality.specular() + ", "
            + quality.specularMips() + ", " + quality.sheen() + ")",
            ImageDesc.of(format, quality.radiance(), quality.radiance()).cube().fullMips());
        Image specular = g.persistent(name + " specular", ImageDesc.of(format, quality.specular(), quality.specular())
            .cube().mips(quality.specularMips()));
        Image sheen = g.persistent(name + " sheen", ImageDesc.of(format, quality.sheen(), quality.sheen()).cube()
            .fullMips());
        Buffer sh = g.persistentBuffer(name + " spherical harmonics", 27 * 4L);
        Pipeline panorama = program(g, "panoramaMain");
        Pipeline uniform = program(g, "uniformMain");
        Pipeline downsample = program(g, "downsampleMain");
        Pipeline ggx = program(g, "ggxMain");
        Pipeline charlie = program(g, "charlieMain");
        Pipeline project = program(g, "shMain");
        Subresource level0 = radiance.mip(0);
        g.pass(name + " radiance").write(level0, Access.STORAGE_WRITE).record(ctx -> {
            if (filters(ctx, radiance)) {
                FilterPush push = new FilterPush().target(ctx.storage(level0)).size(quality.radiance());
                if (environment.source() == Environment.Source.PANORAMA) {
                    push.source(environment.panorama()).sourceSize(environment.panoramaWidth());
                    dispatch(ctx, panorama, push, quality.radiance());
                } else {
                    push.color(environment.color(0), environment.color(1), environment.color(2), 1);
                    dispatch(ctx, uniform, push, quality.radiance());
                }
            }
        });
        for (int mip = 1; mip < radiance.mips(); mip++) {
            Subresource below = radiance.mip(mip - 1);
            Subresource level = radiance.mip(mip);
            int size = quality.radiance() >> mip;
            g.pass(name + " radiance, level " + mip).read(below, Access.STORAGE_READ)
                .write(level, Access.STORAGE_WRITE).record(ctx -> {
                    if (filters(ctx, radiance)) {
                        dispatch(ctx, downsample, new FilterPush().source(ctx.storage(below))
                            .target(ctx.storage(level)).size(size), size);
                    }
                });
        }
        prefilter(g, name + " specular", radiance, specular, ggx, quality.specular());
        prefilter(g, name + " sheen", radiance, sheen, charlie, quality.sheen());
        int shMip = Integer.numberOfTrailingZeros(quality.radiance() / SH_SIZE);
        Subresource shLevel = radiance.mip(shMip);
        g.pass(name + " spherical harmonics").read(shLevel, Access.STORAGE_READ).write(sh, BufferAccess.STORAGE_WRITE)
            .record(ctx -> {
                if (filters(ctx, radiance)) {
                    ctx.dispatch(project, new FilterPush().source(ctx.storage(shLevel)).size(SH_SIZE)
                        .sh(ctx.address(sh)), 1, 1, 1);
                }
            });
        return new Maps(radiance, specular, sheen, sh);
    }

    /** Passes filtering {@code radiance} into each level of {@code target} with {@code pipeline}, by roughness. */
    @AllowAllocation("building the graph, when it changes")
    private void prefilter(GraphBuilder g, String name, Image radiance, Image target, Pipeline pipeline, int size) {
        for (int mip = 0; mip < target.mips(); mip++) {
            Subresource level = target.mip(mip);
            int levelSize = size >> mip;
            float roughness = (float) mip / (target.mips() - 1);
            g.pass(name + ", level " + mip).read(radiance, Access.SAMPLED_COMPUTE).write(level, Access.STORAGE_WRITE)
                .record(ctx -> {
                    if (filters(ctx, radiance)) {
                        dispatch(ctx, pipeline, new FilterPush().source(ctx.sampled(radiance))
                            .target(ctx.storage(level)).size(levelSize).sourceSize(quality.radiance())
                            .sourceMips(radiance.mips()).roughness(roughness).samples(quality.samples()), levelSize);
                    }
                });
        }
    }

    /** Whether this frame filters: every pass of the request asks, and all get one answer. Allocates nothing. */
    private boolean filters(PassContext ctx, Image radiance) {
        return environment.filters(ctx.unsafe().image(radiance), ctx.frame(), ctx.fresh(radiance));
    }

    private static void dispatch(PassContext ctx, Pipeline pipeline, FilterPush push, int size) {
        ctx.dispatch(pipeline, push, (size + 7) / 8, (size + 7) / 8, 6);
    }

    @AllowAllocation("building the graph, when it changes")
    static Pipeline program(GraphBuilder g, String entry) {
        return g.computeProgram("shaders/environment/filter.spv", entry, FilterPush.BYTES);
    }

    /**
     * The BRDF lookup table environment lighting is scaled by (environment/filter.slang's brdfMain): a persistent
     * image, made once per graph, by n.v across and roughness down. A request of its own: every environment shares it.
     */
    public record BrdfTable() implements Request<Image> {

        /** Its size, texels a side, and the samples per texel. */
        static final int SIZE = 128;
        static final int SAMPLES = 512;

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Image build(GraphBuilder g) {
            Image table = g.persistent("environment BRDF table", ImageDesc.of(Format.R16G16B16A16_SFLOAT, SIZE, SIZE));
            Pipeline brdf = program(g, "brdfMain");
            g.pass("environment BRDF table").write(table, Access.STORAGE_WRITE).record(ctx -> {
                if (ctx.fresh(table)) {
                    ctx.dispatch(brdf, new FilterPush().target(ctx.storage(table)).size(SIZE).samples(SAMPLES),
                        (SIZE + 7) / 8, (SIZE + 7) / 8, 1);
                }
            });
            return table;
        }
    }
}
