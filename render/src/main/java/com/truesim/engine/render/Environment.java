package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Arrays;
import java.util.Objects;

/**
 * The light from far away all around a scene, as an image: an equirectangular HDR panorama (a texture of
 * {@link Textures}, linear half floats as {@code RadianceImporter} reads {@code .hdr} files), or one color. An app's
 * object, as {@link Lights} is: views light their surfaces by it with {@link EnvironmentLighting} and show it behind
 * them with {@link SkyBackground}, each view choosing either, both or neither; views sharing it filter it once. Its
 * intensity and rotation apply when it is looked up, so changing them costs nothing; a new image or color is filtered
 * anew, in the frame after ({@link #version}). Later sources (a procedural sky) feed the same filtering.
 *
 * <pre>{@code
 * int sky = textures.add(TextureFormat.RGBA16F, hdr.width(), hdr.height(), Textures.GENERATE, hdr.texels());
 * Environment environment = Environment.panorama("cannon", sky, hdr.width()).intensity(1.5f).yaw(Math.PI / 2);
 * view.port().add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view));
 * }</pre>
 */
public final class Environment {

    /** What an environment's light comes from. */
    public enum Source {
        /** An equirectangular panorama: u around +Y from +X towards +Z, v from +Y (top) to -Y. */
        PANORAMA,
        /** One color in every direction. */
        UNIFORM
    }

    private final String name;
    private volatile Source source;
    private volatile int panorama; // its texture, sampled slot
    private volatile int panoramaWidth;
    private final float[] color = new float[3];
    private volatile float intensity = 1;
    private final float[] rotation = {0, 0, 0, 1};
    private volatile long version = 1;
    // Per cube it is filtered into (its image's handle): the version it holds, the frame that last asked, its answer.
    private long[] cubes = new long[2];
    private long[] cubeVersions = new long[2];
    private long[] cubeFrames = new long[2];
    private boolean[] cubeWork = new boolean[2];
    private int cubeCount;

    private Environment(String name) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("An environment has a name, for the images it is filtered into");
        }
    }

    /**
     * The panorama {@code texture} (a {@link Textures} reference, with mip levels: {@link Textures#GENERATE}),
     * {@code width} texels wide, named {@code name} (unique among a graph's environments).
     */
    public static Environment panorama(String name, int texture, int width) {
        return new Environment(name).setPanorama(texture, width);
    }

    /** One color everywhere (linear), named {@code name}. */
    public static Environment uniform(String name, float r, float g, float b) {
        return new Environment(name).setColor(r, g, b);
    }

    /** Lit by panorama {@code texture} of {@code width} texels from now on: filtered anew. */
    public synchronized Environment setPanorama(int texture, int width) {
        if (texture < 0 || width < 1) {
            throw new IllegalArgumentException("A panorama texture and its width: " + texture + ", " + width);
        }
        panorama = texture & 0xffff;
        panoramaWidth = width;
        source = Source.PANORAMA;
        version++;
        return this;
    }

    /** Lit by one color (linear) from now on: filtered anew. */
    public synchronized Environment setColor(float r, float g, float b) {
        if (!(r >= 0 && g >= 0 && b >= 0)) {
            throw new IllegalArgumentException("A color of 0 or more: " + r + ", " + g + ", " + b);
        }
        color[0] = r;
        color[1] = g;
        color[2] = b;
        source = Source.UNIFORM;
        version++;
        return this;
    }

    /** Its brightness, a factor on all of its light (1: as the image holds it). Read when frames run. */
    public Environment intensity(float intensity) {
        if (!(intensity >= 0)) {
            throw new IllegalArgumentException("An intensity of 0 or more: " + intensity);
        }
        this.intensity = intensity;
        return this;
    }

    /** Turned by unit quaternion ({@code x}, {@code y}, {@code z}, {@code w}): from its frame into the world's. */
    public synchronized Environment rotation(double x, double y, double z, double w) {
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(length > 0)) {
            throw new IllegalArgumentException("A rotation of length 1: " + length);
        }
        rotation[0] = (float) (x / length);
        rotation[1] = (float) (y / length);
        rotation[2] = (float) (z / length);
        rotation[3] = (float) (w / length);
        return this;
    }

    /** Turned by {@code radians} about +Y (counterclockwise seen from above). */
    public Environment yaw(double radians) {
        return rotation(0, Maths.sin(radians / 2), 0, Maths.cos(radians / 2));
    }

    public String name() {
        return name;
    }

    public Source source() {
        return source;
    }

    /** The panorama's sampled slot. */
    public int panorama() {
        return panorama;
    }

    public int panoramaWidth() {
        return panoramaWidth;
    }

    /** Channel {@code c} (0 red, 1 green, 2 blue) of a uniform environment's color. */
    public synchronized float color(int c) {
        return color[c];
    }

    public float intensity() {
        return intensity;
    }

    /** Component {@code i} (x, y, z, w) of the rotation from the world into its frame, the inverse of {@link #rotation}. */
    public synchronized float toEnvironment(int i) {
        return i < 3 ? -rotation[i] : rotation[3];
    }

    /** Changes with its image or color, not with its intensity or rotation. */
    public long version() {
        return version;
    }

    /**
     * Whether a frame filters it into the cube {@code image} (a handle): in frame {@code frame}, when the cube is new
     * ({@code fresh}) or holds an earlier version. Every pass filtering it in one frame gets the same answer, in any
     * order, from any thread. Allocates nothing once a cube has been seen.
     */
    synchronized boolean filters(long image, long frame, boolean fresh) {
        int c = 0;
        while (c < cubeCount && cubes[c] != image) {
            c++;
        }
        if (c == cubeCount) {
            add(image);
        }
        if (cubeFrames[c] != frame) {
            cubeWork[c] = fresh || cubeVersions[c] != version;
            cubeVersions[c] = version;
            cubeFrames[c] = frame;
        }
        return cubeWork[c];
    }

    /** Remembers cube {@code image}, not yet filtered. */
    @AllowAllocation("a cube seen the first time")
    private void add(long image) {
        if (cubeCount == cubes.length) {
            cubes = Arrays.copyOf(cubes, 2 * cubeCount);
            cubeVersions = Arrays.copyOf(cubeVersions, 2 * cubeCount);
            cubeFrames = Arrays.copyOf(cubeFrames, 2 * cubeCount);
            cubeWork = Arrays.copyOf(cubeWork, 2 * cubeCount);
        }
        cubes[cubeCount] = image;
        cubeVersions[cubeCount] = 0;
        cubeFrames[cubeCount] = -1;
        cubeCount++;
    }
}
