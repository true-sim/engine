package com.truesim.engine.render.shadows;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.SurfaceOutput;
import com.truesim.engine.render.ViewOptions;
import java.util.List;
import java.util.Objects;

/**
 * A directional light's shadows by cascaded shadow maps: the camera's view up to {@code maxDistance} cut into
 * {@code cascades} slices, each with a {@code size} x {@code size} map of its own, so the near ones get the detail.
 * Splits follow the practical scheme (a blend of logarithmic and uniform, {@link #lambda}); each cascade is fitted to
 * the sphere around its slice of the frustum and snapped to whole texels of the world, so its map neither grows nor
 * shimmers as the camera turns and moves. Shading picks a pixel's cascade by its view depth and filters it like any
 * shadow map (the entries' filter: PCF today).
 *
 * <p>Each cascade is a {@link SceneDraw} of a view of its own, made with the technique and drawing the casters only,
 * culled with the camera's in one pass.
 * Call {@link #update} every frame once the camera is set, before the frame records: it fits the cascades.
 *
 * <pre>{@code
 * CascadedShadowMaps sunShadows = new CascadedShadowMaps(geometry, lights, 0, 0, camera, 3, 2048,
 *     ViewOptions.shadow(1 << 16, 1 << 20), 300);
 * graph.add(sunShadows);
 * // per frame
 * sunShadows.update();                           // from the camera's world position: cascades snap in world space
 * }</pre>
 */
public final class CascadedShadowMaps implements Technique {

    private final SceneGeometry geometry;
    private final Lights lights;
    private final int light;
    private final int firstEntry;
    private final Camera camera;
    private final int cascades;
    private final int size;
    private final RenderView[] views;
    private final float maxDistance;
    private final Camera[] cameras;
    private final float[] splits;
    private final float[] biases;
    private final float[] normalOffsets;
    private final double[] direction = new double[3];
    private final double[] corner = new double[3];
    private final double[] center = new double[3];
    private float lambda = 0.75f;
    private float biasTexels = 1.5f;
    private float casters = 100;

    /**
     * Shadows of directional light {@code light} of {@code lights} in shadow table entries {@code firstEntry} on, for
     * the view of {@code camera} up to {@code maxDistance}: {@code cascades} maps of {@code size} x {@code size},
     * drawn as views of {@code geometry} made with {@code options} (casters only, whatever the options say).
     */
    @AllowAllocation("created rarely")
    public CascadedShadowMaps(SceneGeometry geometry, Lights lights, int light, int firstEntry, Camera camera,
        int cascades, int size, ViewOptions options, float maxDistance) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.camera = Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(options, "options");
        if (cascades < 1 || size < 1 || !(maxDistance > 0)) {
            throw new IllegalArgumentException("Cascades need a count, a size and a distance: " + cascades + ", "
                + size + ", " + maxDistance);
        }
        this.light = light;
        this.firstEntry = firstEntry;
        this.cascades = cascades;
        this.size = size;
        this.views = new RenderView[cascades];
        for (int i = 0; i < cascades; i++) {
            views[i] = geometry.createView(options.withCastersOnly(true));
        }
        this.maxDistance = maxDistance;
        this.cameras = new Camera[cascades];
        for (int i = 0; i < cascades; i++) {
            cameras[i] = new Camera();
        }
        this.splits = new float[cascades + 1];
        this.biases = new float[cascades];
        this.normalOffsets = new float[cascades];
    }

    /** How the splits blend logarithmic (1) and uniform (0) spacing: 0.75 by default. */
    public CascadedShadowMaps lambda(float lambda) {
        this.lambda = lambda;
        return this;
    }

    /**
     * The bias, in texels (1.5 by default): the lookup moves that far along the surface's normal, and a third of it
     * deep; both scale with each cascade's texel size.
     */
    public CascadedShadowMaps bias(float texels) {
        this.biasTexels = texels;
        return this;
    }

    /** How far behind each cascade's slice, towards the light, casters still cast into it (100 units by default). */
    public CascadedShadowMaps casters(float distance) {
        this.casters = distance;
        return this;
    }

    /** Where cascade {@code i} ends, in view depth (cascade 0 starts at the camera's near plane). */
    public float split(int i) {
        return splits[Objects.checkIndex(i, cascades) + 1];
    }

    /** The view cascade {@code i} is drawn as. */
    public RenderView view(int i) {
        return views[Objects.checkIndex(i, cascades)];
    }

    /** The light camera of cascade {@code i}, as last fitted. */
    public Camera camera(int i) {
        return cameras[Objects.checkIndex(i, cascades)];
    }

    /**
     * Fits the cascades to the camera's current view, from its world position, and the light's direction: each
     * cascade's light camera at a world position of its own, snapped to world texels. Call it every frame after
     * setting the camera, before the frame records. Allocates nothing.
     */
    public void update() {
        double x = camera.position(0);
        double y = camera.position(1);
        double z = camera.position(2);
        float[] viewProjection = camera.viewProjection();
        double near = viewProjection[14]; // reverse depth: depth = near / distance
        splits[0] = (float) near;
        for (int i = 1; i <= cascades; i++) {
            double t = (double) i / cascades;
            double logarithmic = near * Maths.pow(maxDistance / near, t);
            double uniform = near + (maxDistance - near) * t;
            splits[i] = (float) (lambda * logarithmic + (1 - lambda) * uniform);
        }
        lights.direction(light, direction);
        double dx = direction[0];
        double dy = direction[1];
        double dz = direction[2];
        // The light's axes: back (towards the light), right and up, as Camera.setOrthographic makes them.
        double ux = Math.abs(dy) < 0.99 ? 0 : 1;
        double uy = Math.abs(dy) < 0.99 ? 1 : 0;
        double bx = -dx;
        double by = -dy;
        double bz = -dz;
        double rx = uy * bz;
        double ry = -ux * bz;
        double rz = ux * by - uy * bx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double tx = by * rz - bz * ry;
        double ty = bz * rx - bx * rz;
        double tz = bx * ry - by * rx;
        float[] inverse = camera.inverseViewProjection();
        for (int i = 0; i < cascades; i++) {
            // The sphere around the frustum's slice: its corners averaged, the farthest corner's distance.
            center[0] = 0;
            center[1] = 0;
            center[2] = 0;
            for (int c = 0; c < 8; c++) {
                corner(inverse, near, splits[i + (c >> 2)], (c & 1) * 2 - 1, (c >> 1 & 1) * 2 - 1);
                center[0] += corner[0] / 8;
                center[1] += corner[1] / 8;
                center[2] += corner[2] / 8;
            }
            double radius = 0;
            for (int c = 0; c < 8; c++) {
                corner(inverse, near, splits[i + (c >> 2)], (c & 1) * 2 - 1, (c >> 1 & 1) * 2 - 1);
                double ex = corner[0] - center[0];
                double ey = corner[1] - center[1];
                double ez = corner[2] - center[2];
                radius = Math.max(radius, Math.sqrt(ex * ex + ey * ey + ez * ez));
            }
            radius = Math.ceil(radius * 16) / 16; // steady as the camera turns
            // Snapped to whole texels of the world along the light's right and up.
            double texel = 2 * radius / size;
            double wx = x + center[0];
            double wy = y + center[1];
            double wz = z + center[2];
            double alongRight = rx * wx + ry * wy + rz * wz;
            double alongUp = tx * wx + ty * wy + tz * wz;
            double shiftRight = Math.floor(alongRight / texel) * texel - alongRight;
            double shiftUp = Math.floor(alongUp / texel) * texel - alongUp;
            double cx = center[0] + rx * shiftRight + tx * shiftUp;
            double cy = center[1] + ry * shiftRight + ty * shiftUp;
            double cz = center[2] + rz * shiftRight + tz * shiftUp;
            double back = radius + casters;
            double depth = back + radius;
            // At the slice's snapped center in the world, looking from back along the light.
            cameras[i].position(x + cx, y + cy, z + cz).setOrthographic(bx * back, by * back, bz * back, dx, dy, dz,
                ux, uy, 0, radius, radius, depth, size, size);
            biases[i] = (float) (biasTexels / 3 * texel / depth);
            normalOffsets[i] = (float) (biasTexels * texel);
        }
    }

    /** Corner ({@code ndcX}, {@code ndcY}) of the view at {@code distance}, camera-relative, into {@link #corner}. */
    private void corner(float[] inverse, double near, double distance, int ndcX, int ndcY) {
        double ndcZ = near / distance;
        double w = inverse[3] * ndcX + inverse[7] * ndcY + inverse[11] * ndcZ + inverse[15];
        for (int k = 0; k < 3; k++) {
            corner[k] = (inverse[k] * ndcX + inverse[4 + k] * ndcY + inverse[8 + k] * ndcZ + inverse[12 + k]) / w;
        }
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
        ShadowInputs.Set inputs = g.request(new ShadowInputs());
        Image[] maps = new Image[cascades];
        for (int i = 0; i < cascades; i++) {
            maps[i] = g.request(new SceneDraw(geometry, views[i], cameras[i], SurfaceOutput.depth(),
                VK_FORMAT_D32_SFLOAT, size, size, false)).depth();
            inputs.sample(maps[i]);
        }
        inputs.setup(ctx -> {
            for (int i = 0; i < cascades; i++) {
                lights.shadowMap(firstEntry + i, cameras[i], ctx.sampled(maps[i]), size, biases[i], splits[i + 1],
                    normalOffsets[i]);
            }
            lights.shadows(light, firstEntry, cascades);
        });
    }
}
