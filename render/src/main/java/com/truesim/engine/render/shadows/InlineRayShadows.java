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
import com.truesim.engine.render.ShadowInputs;
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
    private final MeshletRenderer renderer;
    private final Lights lights;
    private final int light;
    private final int entry;
    private float offset = 2.0e-3f;

    /** Shadows of light {@code light} of {@code lights}, traced into {@code renderer}'s instances, in {@code entry}. */
    public InlineRayShadows(RayTracing rayTracing, MeshletRenderer renderer, Lights lights, int light, int entry) {
        this.rayTracing = Objects.requireNonNull(rayTracing, "rayTracing");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.light = light;
        this.entry = entry;
    }

    /** How far rays start off the surface, per unit of distance from the camera (2e-3 by default). */
    public InlineRayShadows offset(float perUnit) {
        this.offset = perUnit;
        return this;
    }

    @Override
    public List<Key> provides() {
        return List.of();
    }

    @Override
    public List<Key> modifies() {
        return List.of(Keys.LIGHT_LIST);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        g.modifyBuffer(Keys.LIGHT_LIST);
        Buffer topLevel = g.request(new TopLevel(rayTracing, renderer));
        g.request(new ShadowInputs()).after(topLevel).rays().setup(ctx -> {
            lights.shadowRay(entry, rayTracing.slot(), offset);
            lights.shadows(light, entry, 1);
        });
    }
}
