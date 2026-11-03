package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import java.util.List;

/**
 * The surface outputs of a view's opaque shading pass: what later passes know of each pixel's surface, written by the
 * pass that shades it (forward on both meshlet paths and classic LOD, or the visibility buffer's shading) only when a
 * technique of the view asks for them ({@link Consumer}); without one, no image, no attachment and no shader writes
 * them. Provided under {@link Keys#SURFACE_NORMAL}, {@link Keys#SURFACE_MATERIAL} and {@link Keys#SURFACE_REFLECTANCE}
 * (surfaces.slang has the encodings):
 *
 * <ul>
 *   <li><b>normal</b> ({@link #NORMAL_FORMAT}): the shading normal in view space (x right, y down, z forward), folded
 *   onto the octahedron, two 16-bit signed normalized values, x in the low half;
 *   <li><b>material</b> ({@link #MATERIAL_FORMAT}): specular F0 (a metal's albedo) in rgb, perceptual roughness in a;
 *   <li><b>reflectance</b> ({@link #REFLECTANCE_FORMAT}): in rgb, what the environment's reflection along the mirror
 *   direction at the pixel's roughness is weighed by in its color (the split sum's scale and bias, the occlusion, what
 *   a sheen or a clear coat over it lets through; 0 where shading bent the reflection, or nothing covers the pixel),
 *   so a technique can replace that term ({@link ScreenSpaceReflections}); a is 1 where a surface was shaded.
 * </ul>
 *
 * <p>Post materials will read them the same way: a technique implementing {@link Consumer} and requiring the keys.
 * Blended and transmissive surfaces write none (their pixels show the opaque surface's).
 */
public final class SurfaceOutputs {

    /** The normal's format: two 16-bit signed normalized values in a word (surfaces.slang's packNormal). */
    public static final Format NORMAL_FORMAT = Format.R32_UINT;
    /** The material's: F0, perceptual roughness. */
    public static final Format MATERIAL_FORMAT = Format.R8G8B8A8_UNORM;
    /** The reflectance's. */
    public static final Format REFLECTANCE_FORMAT = Format.R8G8B8A8_UNORM;

    private SurfaceOutputs() {
    }

    /** A technique reading a view's surface outputs: it asks for them while it wants them. */
    public interface Consumer {

        /**
         * Whether the view's shading should write the surface outputs this build; read while the graph builds (a
         * setting read through {@code g.setting} rebuilds the graph when it changes).
         */
        boolean wantsSurfaces(GraphBuilder g);
    }

    /** The three images. */
    public record Images(Image normal, Image material, Image reflectance) {

        /** All three, in the order of the shaders' targets 1 to 3. */
        @AllowAllocation("building the graph, when it changes")
        public List<Image> all() {
            return List.of(normal, material, reflectance);
        }
    }

    /** Whether a technique the building one sees (its view's, the frame's) wants the surface outputs. */
    @AllowAllocation("building the graph, when it changes")
    public static boolean wanted(GraphBuilder g) {
        for (Technique technique : g.techniquesInView()) {
            if (technique instanceof Consumer consumer && consumer.wantsSurfaces(g)) {
                return true;
            }
        }
        return false;
    }

    /** The images of the surface outputs, {@code width} by {@code height}, named after {@code name}. */
    @AllowAllocation("building the graph, when it changes")
    static Images create(GraphBuilder g, String name, int width, int height) {
        return new Images(g.create(name + " normal", ImageDesc.of(NORMAL_FORMAT, width, height)),
            g.create(name + " material", ImageDesc.of(MATERIAL_FORMAT, width, height)),
            g.create(name + " reflectance", ImageDesc.of(REFLECTANCE_FORMAT, width, height)));
    }

    /**
     * Provides {@code images} under the surface keys; with none (not wanted), images nothing writes, which no pass may
     * read (the graph fails a build reading one) and which take no memory.
     */
    @AllowAllocation("building the graph, when it changes")
    static void provide(GraphBuilder g, Images images, int width, int height) {
        Images provided = images != null ? images : create(g, "unwritten surface", width, height);
        g.provide(Keys.SURFACE_NORMAL, provided.normal());
        g.provide(Keys.SURFACE_MATERIAL, provided.material());
        g.provide(Keys.SURFACE_REFLECTANCE, provided.reflectance());
    }

    /** The keys shading techniques provide them under. */
    static final List<com.truesim.engine.graph.Key<?>> KEYS = List.of(Keys.SURFACE_NORMAL, Keys.SURFACE_MATERIAL,
        Keys.SURFACE_REFLECTANCE);
}
