// For tests and the benchmark (DisplacementTest, DisplacementBenchmark): grass blades with a rest posture of their own,
// bent by the scene's wind (the test.wind hook's record). A test material; real vegetation belongs in a plugin.
//
// The mesh: each blade a strip standing straight up from its root at y = 0 (mesh space), v (texture) its height on the
// blade (0 at the root, 1 at the tip), the vertex's height its distance along the blade, its UV1 the root's x and z
// (the same for every vertex of the blade: what the blade's hash and pivot are made of).
//
// Each blade bends along a circular arc (constant curvature: the slope grows towards the tip) by its bend B, a
// horizontal vector of the tip's angle: its rest bend (a random share of `rest`, across its face either way) plus the
// wind's. The wind's is the steady lean plus gusts, both by strength squared (drag), divided by the blade's stiffness;
// gusts are 2D value noise travelling with the wind in world space, so they roll across the field rather than along a
// line, and each blade adds its own sway (its own rate and phase) and a little turn of the wind's direction. The arc
// keeps the blade's length (vertices are turned about the root, not sheared), the cross-section is turned with it, and
// the tips twist (flutter) about the blade. Normals turn as the surface does. Everything that moves reads the hook
// record's time, so the frame before's evaluation (motion vectors) uses the frame before's wind.
module grass;

import materials;
import test.hooks.testWind;
__exported import test.grassParameters;

/** test.wind's index in the shading hooks' dispatch, resolved when a shader links (hooks.properties). */
extern static const uint RUN_HOOK_TEST_WIND_INDEX;

/** The largest bend of a tip (radians): bends approach it softly. The material's declared reach assumes it. */
static const float GRASS_MAX_BEND = 1.5;

/** A 32-bit hash (PCG). */
uint grassHash(uint v)
{
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

/** The hash's next value in [0, 1), advancing it. */
float grassRandom(inout uint h)
{
    h = grassHash(h);
    return float(h >> 8) / 16777216.0;
}

/** Value noise in [0, 1] at `x`, smooth between the integer lattice's random values. */
float grassNoise(float2 x)
{
    float2 cell = floor(x);
    float2 f = x - cell;
    float2 u = f * f * (3.0 - 2.0 * f);
    int2 c = int2(cell);
    uint base = uint(c.x) * 73856093u ^ uint(c.y) * 19349663u;
    float a = float(grassHash(base) >> 8) / 16777216.0;
    float b = float(grassHash(base ^ 73856093u) >> 8) / 16777216.0;
    float d = float(grassHash(base ^ 19349663u) >> 8) / 16777216.0;
    float e = float(grassHash(base ^ 73856093u ^ 19349663u) >> 8) / 16777216.0;
    return lerp(lerp(a, b, u.x), lerp(d, e, u.x), u.y);
}

/** `v` turned by `angle` about the unit axis `k` (Rodrigues). */
float3 grassTurn(float3 v, float3 k, float angle)
{
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(k, v) * s + k * (dot(k, v) * (1.0 - c));
}

[MaterialType("grass")]
public struct Grass : IMaterial
{
    public typealias Parameters = GrassParameters;

    public static Surface surface(GrassParameters p, MaterialRef m, SurfaceInput in)
    {
        Surface s = Surface.defaults(normalize(in.normal));
        s.albedo = lerp(p.root, p.tip, saturate(in.uv.y));
        s.roughness = 0.8;
        return s;
    }

    public override static Displaced displace(GrassParameters p, MaterialRef m, DisplacePoint in)
    {
        Displaced d = Displaced.of(in);
        // The blade: its root, the vertex's distance along it and offset across it, its face's normal
        float3 root = float3(in.uv1.x, 0, in.uv1.y);
        float v = saturate(in.uv.y);
        float along = in.position.y;
        float3 across = float3(in.position.x - root.x, 0, in.position.z - root.z);
        // Its own random values, the same for every vertex of it (the root's bits and the instance)
        uint h = grassHash(asuint(in.uv1.x) ^ grassHash(asuint(in.uv1.y) ^ grassHash(in.instance)));
        float side = grassRandom(h) < 0.5 ? -1.0 : 1.0;
        float restTurn = (grassRandom(h) - 0.5) * 0.9;
        float restShare = 0.2 + 0.8 * grassRandom(h);
        float stiffness = max(p.stiffness * (0.75 + 0.5 * grassRandom(h)), 1e-3);
        float windTurn = (grassRandom(h) - 0.5) * 0.4;
        float swayRate = 0.6 + 0.6 * grassRandom(h);
        float phase = grassRandom(h);
        float flutterRate = 2.0 + 1.5 * grassRandom(h);
        // At rest: across its face (the way a flat blade bends easiest), either way, turned a little
        float2 face = normalize(in.normal.xz + float2(1e-6, 0));
        float cr = cos(restTurn);
        float sr = sin(restTurn);
        float2 bend = side * float2(face.x * cr - face.y * sr, face.x * sr + face.y * cr) * (p.rest * restShare);
        float twist = 0;
        if (in.hookOn(RUN_HOOK_TEST_WIND_INDEX))
        {
            TestWindParameters* wind = in.hook<TestWindParameters>(RUN_HOOK_TEST_WIND_INDEX);
            float3 direction = wind[0].direction;
            float strength = wind[0].strength;
            float time = wind[0].time;
            float frequency = wind[0].frequency;
            float wavelength = max(wind[0].wavelength, 1e-3);
            // Gusts at the root: two octaves of noise drifting with the wind (gust fronts frequency a second), the
            // finer a little faster and across, so the pattern changes as it travels
            float3 rootWorld = in.origin + (in.originLow + in.toWorld(root));
            float2 flow = normalize(direction.xz + float2(1e-6, 0));
            float2 at = rootWorld.xz / wavelength - flow * (time * frequency);
            float2 fine = rootWorld.xz * (2.3 / wavelength) - (flow * 1.6 + float2(-flow.y, flow.x) * 0.5)
                * (time * frequency);
            float gust = smoothstep(0.3, 0.8, 0.65 * grassNoise(at) + 0.35 * grassNoise(fine + 17.0));
            // Drag grows with the wind's speed squared; the blade's own sway rides on it
            float drag = strength * strength / stiffness;
            float sway = sin(6.2831853 * (time * swayRate + phase));
            float angle = drag * (p.lean * (1.0 + 0.15 * sway) + p.gust * gust);
            // The wind's direction in the mesh, turned a little for each blade and by the gust
            float2 downwind = normalize(in.toMesh(float3(flow.x, 0, flow.y)).xz + float2(1e-6, 0));
            float turn = windTurn + (gust - 0.5) * 0.25;
            float ct = cos(turn);
            float st = sin(turn);
            bend += float2(downwind.x * ct - downwind.y * st, downwind.x * st + downwind.y * ct) * angle;
            twist = p.flutter * strength * (0.4 + 0.6 * gust) * sin(6.2831853 * (time * flutterRate + phase));
        }
        float size = length(bend);
        float tipAngle = GRASS_MAX_BEND * tanh(size / GRASS_MAX_BEND);
        float3 toward = size > 1e-6 ? float3(bend.x, 0, bend.y) / size : float3(1, 0, 0);
        // On a circular arc from the root, upright there: at distance s the slope's angle is tipAngle * v
        float phi = tipAngle * v;
        float outward = abs(phi) < 1e-3 ? along * phi * 0.5 : along * (1.0 - cos(phi)) / phi;
        float up = abs(phi) < 1e-3 ? along : along * sin(phi) / phi;
        // The cross-section twisted about the blade, then turned with the arc's slope
        float3 axis = float3(toward.z, 0, -toward.x); // up turns towards `toward` about it
        float spin = twist * v * v;
        float3 section = grassTurn(grassTurn(across, float3(0, 1, 0), spin), axis, phi);
        d.position = root + toward * outward + float3(0, up, 0) + section;
        d.normal = normalize(grassTurn(grassTurn(in.normal, float3(0, 1, 0), spin), axis, phi));
        return d;
    }
};
