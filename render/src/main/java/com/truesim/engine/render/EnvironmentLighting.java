package com.truesim.engine.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.layout.IndirectConstants;
import com.truesim.engine.render.layout.IndirectLightLayout;
import com.truesim.engine.vulkan.Commands;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;

/**
 * A view's surfaces lit by an {@link Environment}: it provides the view's {@link Keys#INDIRECT_LIGHT} table, its
 * environment section filled from the filtered environment ({@link EnvironmentMaps}, shared by every view of the
 * environment) and the lookup table, and gathers their images for shading ({@link IndirectInputs}). Every way of
 * shading then adds the environment's diffuse light and reflections, for every material feature, as the glTF sample
 * viewer does; later indirect techniques (probe volumes, reflection probes, screen-space reflections) modify the same
 * table. One per view, under one id; a view without one is lit by the constant sky. Its background is
 * {@link SkyBackground}'s, a technique of its own.
 *
 * <pre>{@code
 * view.port().add(new EnvironmentLighting(environment, view));
 * }</pre>
 */
public final class EnvironmentLighting implements Technique {

    /** The id of the technique providing a view's indirect light. */
    public static final Id<EnvironmentLighting> ID = Id.of("truesim.indirectLight", EnvironmentLighting.class);

    private final Environment environment;
    private final SceneView view;
    private final EnvironmentMaps.Quality quality;

    /** {@code view} lit by {@code environment}, filtered at {@link EnvironmentMaps#QUALITY}. */
    public EnvironmentLighting(Environment environment, SceneView view) {
        this(environment, view, EnvironmentMaps.QUALITY);
    }

    /** {@code view} lit by {@code environment}, filtered at {@code quality}. */
    public EnvironmentLighting(Environment environment, SceneView view, EnvironmentMaps.Quality quality) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.view = Objects.requireNonNull(view, "view");
        this.quality = Objects.requireNonNull(quality, "quality");
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of(Keys.INDIRECT_LIGHT);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        view.checkBuilding(g, this);
        EnvironmentMaps.Maps maps = g.request(new EnvironmentMaps(environment, quality));
        Image brdf = g.request(new EnvironmentMaps.BrdfTable());
        Buffer table = g.createBuffer("indirect light", IndirectLightLayout.BYTES);
        int specularMips = maps.specular().mips();
        int sheenMips = maps.sheen().mips();
        // The table each frame: the cubes' slots, the intensity and rotation as they are now, the harmonics copied in.
        g.pass("indirect light").write(table, BufferAccess.TRANSFER_DST).read(maps.sh(), BufferAccess.TRANSFER_SRC)
            .read(maps.specular(), Access.SAMPLED_FRAGMENT).read(maps.sheen(), Access.SAMPLED_FRAGMENT)
            .read(brdf, Access.SAMPLED_FRAGMENT)
            .record(ctx -> write(ctx, table, maps, brdf, specularMips, sheenMips));
        g.request(new IndirectInputs(g.view())).sample(maps.specular()).sample(maps.sheen()).sample(brdf);
        g.provide(Keys.INDIRECT_LIGHT, table);
    }

    /** Writes the table's header and copies the harmonics in. Allocates nothing. */
    private void write(PassContext ctx, Buffer table, EnvironmentMaps.Maps maps, Image brdf, int specularMips,
        int sheenMips) {
        try (MemoryStack stack = stackPush()) {
            long data = stack.ncalloc(16, 1, IndirectLightLayout.SH);
            memPutInt(data + IndirectLightLayout.FLAGS, IndirectConstants.INDIRECT_ENVIRONMENT);
            memPutInt(data + IndirectLightLayout.SPECULAR, ctx.sampled(maps.specular()));
            memPutInt(data + IndirectLightLayout.SHEEN, ctx.sampled(maps.sheen()));
            memPutInt(data + IndirectLightLayout.BRDF, ctx.sampled(brdf));
            memPutInt(data + IndirectLightLayout.SPECULAR_MIPS, specularMips);
            memPutInt(data + IndirectLightLayout.SHEEN_MIPS, sheenMips);
            memPutFloat(data + IndirectLightLayout.INTENSITY, environment.intensity());
            for (int i = 0; i < 4; i++) {
                memPutFloat(data + IndirectLightLayout.ROTATION + 4L * i, environment.toEnvironment(i));
            }
            long handle = ctx.unsafe().buffer(table);
            Commands.updateBuffer(ctx.unsafe().commands(), handle, 0, IndirectLightLayout.SH, data);
        }
        ctx.copy(maps.sh(), 0, table, IndirectLightLayout.SH, 27 * 4L);
    }
}
