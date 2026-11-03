package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.AudioMaterial;
import com.truesim.scene.audio.Transmission;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Occlusion rays through traced geometry (§2.11 of scene's audio.md): a {@link BatchedAudioWorld} over a
 * {@link SegmentTracer}, each surface a ray crosses a crossing whose material id a table maps to an
 * {@link AudioMaterial} (null: open). Give it to {@link AudioSync#occlusion(BatchedAudioWorld)}.
 *
 * <p>With the renderer's ray tracing ({@code SegmentTracing}), the app joins the two and says how crossings count, as
 * {@code CollisionAudioWorld}'s do: {@code frontsOnly(true)} counts a closed mesh once, going in, as physics counts a
 * convex body (walls, rocks, buildings: meshes whose faces wind outwards, as glTF's do); {@code frontsOnly(false)}
 * counts each triangle crossed, as physics counts a triangle mesh's sides (single panes, open shells). Its material ids
 * are the scene's ({@code SceneResources.materialOf} names their assets), read on the thread collecting. Without ray
 * tracing, occlude through physics ({@code CollisionAudioWorld}) or not at all.
 */
public final class TracedAudioWorld implements BatchedAudioWorld {

    private final SegmentTracer tracer;
    private final IntFunction<AudioMaterial> materials;
    private final int[] words;

    /** Rays through {@code tracer}, its crossings' material ids mapped by {@code materials} to what they let through. */
    @AllowAllocation("set up once")
    public TracedAudioWorld(SegmentTracer tracer, IntFunction<AudioMaterial> materials) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.materials = Objects.requireNonNull(materials, "materials");
        words = new int[tracer.words() * tracer.capacity()];
    }

    @Override
    public int capacity() {
        return tracer.capacity();
    }

    @Override
    public long submit(double[] rays, int count) {
        return tracer.submit(rays, count);
    }

    @Override
    public int collect(long ticket, Transmission[] out, int offset) {
        int count = tracer.collect(ticket, words);
        int stride = tracer.words();
        for (int i = 0; i < count; i++) {
            Transmission t = out[offset + i].open();
            int w = i * stride;
            for (int k = 0; k < words[w]; k++) {
                AudioMaterial material = materials.apply(words[w + 2 + 2 * k]);
                t.cross(material == null ? AudioMaterial.OPEN : material);
            }
        }
        return count;
    }
}
