package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.layout.TransparencyPush;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;

/**
 * Blended materials ({@link Material#alphaBlend}), drawn over the opaque scene by the technique the setting
 * {@link #MODE} chooses: shading techniques call {@link #add} once their color and depth are made. The blended raster
 * bin's meshlets come from the same culling as the opaque ones.
 */
public final class Transparency {

    /** How blended materials are drawn. */
    public enum Mode {
        /**
         * Weighted blended order-independent transparency (McGuire and Bavoil 2013): one pass, no sorting, nearer and
         * more opaque surfaces weighted more; an approximation where very different opacities overlap a lot.
         */
        WEIGHTED_BLENDED,
        /** As opaque (double-sided, no blending): the cheapest, for low settings. */
        OPAQUE
    }

    /** How blended materials are drawn; the graph rebuilds when it changes. */
    public static final Setting<Mode> MODE = Setting.choice("render.transparency", Mode.WEIGHTED_BLENDED,
        "how alpha-blended materials are drawn");

    /** The accumulation target's format: weighted color and alpha, added up. */
    public static final int ACCUMULATED_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    /** The revealage target's format: what the blended surfaces let through. */
    public static final int REVEALAGE_FORMAT = org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT;

    private Transparency() {
    }

    /**
     * What providers draw blended meshlets into ({@link GeometryProvider#drawBlended}), within {@code region} (null:
     * all of it): the two targets, the opaque depth (tested, not written), and what shading reads.
     *
     * @param accumulated weighted color times alpha, and alpha, added up (cleared to 0)
     * @param revealage the product of 1 - alpha (cleared to 1)
     * @param depth the opaque surfaces' depth
     * @param lights the light list, or null
     * @param shadows the shadow inputs, or null
     * @param region the render region, or null
     */
    public record Targets(Image accumulated, Image revealage, Image depth, Buffer lights, ShadowInputs.Set shadows,
        ViewPort region) {

        /** {@code pass}, drawing within the render region of {@link #region} where there is one. */
        public GraphBuilder.PassBuilder inRegion(GraphBuilder.PassBuilder pass) {
            return region == null ? pass : pass.region(region);
        }
    }

    /**
     * Draws {@code draw}'s blended meshlets over {@code color} (the opaque scene, its depth {@code depth}) as the graph's
     * {@link #MODE} says, returning the scene's color with them: with weighted blending, into two targets resolved over
     * a copy of {@code color}; as opaque, {@code color} itself (the opaque passes drew them).
     */
    @AllowAllocation("building the graph, when it changes")
    public static Image add(GraphBuilder g, SceneDraw draw, Image color, Image depth, Buffer lights,
        ShadowInputs.Set shadows, ViewPort region) {
        Materials materials = draw.geometry().renderer().scene().materials();
        if (g.setting(MODE) != Mode.WEIGHTED_BLENDED || materials == null || !materials.hasBlended()) {
            return color;
        }
        String name = draw.view().index() == 0 ? "scene" : "view " + draw.view().index();
        Image accumulated = g.create(name + " blended color", ImageDesc.of(Format.of(ACCUMULATED_FORMAT),
            color.width(), color.height()));
        Image revealage = g.create(name + " blended revealage", ImageDesc.of(Format.of(REVEALAGE_FORMAT),
            color.width(), color.height()));
        Targets targets = new Targets(accumulated, revealage, depth, lights, shadows, region);
        boolean load = false;
        for (GeometryProvider provider : draw.geometry().providers()) {
            provider.drawBlended(g, draw, targets, load);
            load = true;
        }
        Pipeline resolve = g.computeProgram("shaders/blocks/transparency.spv", "transparencyMain",
            TransparencyPush.BYTES);
        Image result = g.create(name + " color with blended", ImageDesc.like(color, com.truesim.engine.graph.Ratio.ONE));
        TransparencyPush push = new TransparencyPush();
        g.pass(name + " transparency resolve")
            .read(color, Access.STORAGE_READ)
            .read(accumulated, Access.STORAGE_READ)
            .read(revealage, Access.STORAGE_READ)
            .write(result, Access.STORAGE_WRITE)
            .record(ctx -> {
                int width = region != null ? ctx.regionWidth(color) : color.width();
                int height = region != null ? ctx.regionHeight(color) : color.height();
                ctx.dispatch(resolve, push.opaque(ctx.storage(color)).color(ctx.storage(result))
                    .accumulated(ctx.storage(accumulated)).revealage(ctx.storage(revealage)).size(width, height),
                    (width + 7) / 8, (height + 7) / 8, 1);
            });
        return result;
    }

    /**
     * What a shading technique's build version takes in for transparency: whether {@code materials} has blended ones,
     * so the transparency pass comes and goes with them.
     */
    static long buildVersion(Materials materials) {
        return materials != null && materials.hasBlended() ? 1 : 0;
    }

    /** Whether the opaque passes draw the blended bin too: as {@link Mode#OPAQUE}. */
    static boolean opaque(GraphBuilder g) {
        return g.setting(MODE) == Mode.OPAQUE;
    }
}
