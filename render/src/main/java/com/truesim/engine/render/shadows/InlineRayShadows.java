package com.truesim.engine.render.shadows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.engine.settings.EngineSettings;
import java.util.List;
import java.util.Objects;

/**
 * A light's shadows traced inline: shading itself casts one ray query per pixel towards the light, through shadow table
 * entry {@code entry}. No mask and no extra pass beyond the frame's top level ({@link TopLevel}, shared). The
 * visibility buffer's shading traces them, once per shaded pixel; shading that cannot trace (forward shading, or a
 * device without ray tracing) counts them as lit: use {@link RayTracedShadows} there.
 */
public final class InlineRayShadows implements Technique {

    private final RayTracing rayTracing;
    private final RenderView view;
    private final Lights lights;
    private final int light;
    private final int entry;
    private Float offset; // or null: as the graph's settings say

    /**
     * Shadows of light {@code light} of {@code lights}, traced into a top level of {@code view}'s scene relative to its
     * camera (the view shading them), in {@code entry}.
     */
    public InlineRayShadows(RayTracing rayTracing, RenderView view, Lights lights, int light, int entry) {
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.view = Objects.requireNonNull(view, "view");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.light = light;
        this.entry = entry;
    }

    /** How far rays start off the surface, per unit of distance from the camera, overriding {@link ShadowSettings#RAY_OFFSET}. */
    @AllowAllocation("set up rarely")
    public InlineRayShadows offset(float perUnit) {
        this.offset = perUnit;
        return this;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.LIGHT_LIST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modify(Keys.LIGHT_LIST);
        Buffer topLevel = g.request(new TopLevel(rayTracing, view.renderer()));
        ShadowTable table = lights.shadowTable(g.view());
        EngineSettings settings = g.settings();
        g.request(new ShadowInputs(g.view())).rays(topLevel).setup(ctx -> {
            table.shadowRay(entry, rayTracing.slot(), offset != null ? offset : settings.get(ShadowSettings.RAY_OFFSET),
                rayTracing.origin(0), rayTracing.origin(1),
                rayTracing.origin(2)); // the top level is relative to the origin
            table.light(light, entry, 1);
        });
    }
}
