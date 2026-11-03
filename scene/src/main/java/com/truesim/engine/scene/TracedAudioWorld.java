package com.truesim.engine.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.audio.BatchedAudioWorld;
import com.truesim.engine.render.SegmentTracing;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.audio.AudioMaterial;
import com.truesim.scene.audio.Transmission;
import com.truesim.scene.render.MaterialData;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Occlusion rays through the ray tracing structures (§2.11 of scene's audio.md): a {@link BatchedAudioWorld} over a
 * {@link SegmentTracing}, each triangle a ray crosses a crossing whose material id a table maps to an
 * {@link AudioMaterial} (null: open), up to {@link SegmentTracing#MAX_CROSSINGS}. Give it to
 * {@code AudioSync.occlusion}; the tracing goes into the main view of the frame graph drawing the scene.
 *
 * <p>How crossings count follows {@code CollisionAudioWorld}'s: with {@link Crossings#ENTERING} a closed mesh counts
 * once, going in, as physics counts a convex body (walls, rocks, buildings: meshes whose faces wind outwards, as glTF's
 * do); with {@link Crossings#EVERY} each triangle crossed counts, as physics counts a triangle mesh's sides (single
 * panes, open shells). Both match physics' results for the same geometry and materials, rays starting inside a body
 * included. Instances casting no shadows are not in the top level, so they let everything through.
 *
 * <p>Collected on the thread that updates the scene's renderables (the materials' ids are read then). On devices
 * without ray tracing there is no {@code SegmentTracing}: occlude through physics ({@code CollisionAudioWorld}) or not
 * at all.
 */
public final class TracedAudioWorld implements BatchedAudioWorld {

    /** Which triangles crossed count. */
    public enum Crossings {
        /** Only those a ray goes into a mesh through (front faces): a closed mesh once, as a convex body. */
        ENTERING,
        /** Every one: a mesh once per side crossed, as a triangle mesh in physics. */
        EVERY
    }

    private final SegmentTracing tracing;
    private final IntFunction<AudioMaterial> materials;
    private final int[] words;

    /**
     * Rays through {@code tracing}'s top level, materials by their id in the scene's material table, counted as
     * {@code crossings} says (set on the tracing).
     */
    @AllowAllocation("set up once")
    public TracedAudioWorld(SegmentTracing tracing, IntFunction<AudioMaterial> materials, Crossings crossings) {
        this.tracing = Objects.requireNonNull(tracing, "tracing");
        this.materials = Objects.requireNonNull(materials, "materials");
        tracing.frontsOnly(Objects.requireNonNull(crossings, "crossings") == Crossings.ENTERING);
        words = new int[SegmentTracing.WORDS * tracing.capacity()];
    }

    /**
     * Rays through {@code tracing}'s top level, a triangle's material asset (as {@code resources} hold it) mapped by
     * {@code materials} to what it lets through.
     */
    @AllowAllocation("set up once")
    public static TracedAudioWorld byAsset(SegmentTracing tracing, SceneResources resources,
        Function<AssetRef<MaterialData>, AudioMaterial> materials, Crossings crossings) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(materials, "materials");
        return new TracedAudioWorld(tracing, id -> {
            AssetRef<MaterialData> material = resources.materialOf(id);
            return material == null ? null : materials.apply(material);
        }, crossings);
    }

    @Override
    public int capacity() {
        return tracing.capacity();
    }

    @Override
    public long submit(double[] rays, int count) {
        return tracing.submit(rays, count);
    }

    @Override
    public int collect(long ticket, Transmission[] out, int offset) {
        int count = tracing.collect(ticket, words);
        for (int i = 0; i < count; i++) {
            Transmission t = out[offset + i].open();
            int w = i * SegmentTracing.WORDS;
            for (int k = 0; k < words[w]; k++) {
                AudioMaterial material = materials.apply(words[w + 2 + 2 * k]);
                t.cross(material == null ? AudioMaterial.OPEN : material);
            }
        }
        return count;
    }

    /** The tracing it traces through. */
    public SegmentTracing tracing() {
        return tracing;
    }
}
