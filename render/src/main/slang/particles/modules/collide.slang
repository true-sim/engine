// The built-in collision methods of the GPU (E10, E24): the frame's colliders, the terrain's height field and the
// collision view's last depth. Each tests the particle's move over the step (in world axes about its emitter's
// origin) and says how it responds; the first hit of a program responds (simulate.slang's step). Colliders and
// heights as scene-effects' CpuSimulation (Collide).
module collide;

import particles.particle;

/** A collision method's radius: the particle's half size where it is 0. */
public float radiusOf(float radius, Particle p)
{
    return radius > 0 ? radius : 0.5 * p.size.x;
}

/** `hit` with a method's response (RESPONSE_*, rounded), friction and restitution. */
public ParticleHit respond(ParticleHit hit, float response, float friction, float restitution)
{
    hit.response = uint(round(response));
    hit.friction = friction;
    hit.restitution = restitution;
    return hit;
}

/** Whether `q` from a collider's center `c` is within `reach` of it: pushed out along it (+Y from the very center). */
public ParticleHit outOf(float3 c, float3 q, float reach)
{
    float d = length(q);
    if (!(d < reach))
    {
        return noHit();
    }
    float3 n = d > 1e-6 ? q / d : float3(0, 1, 0);
    return hitAt(c + n * reach, n);
}

/** Whether a particle of radius `r` moving from `a` to `b` ends touching collider `k` (centered at `c`). */
public ParticleHit hitCollider(ParticleCollider k, float3 c, float r, float3 a, float3 b)
{
    float3 q = b - c;
    if (k.kind == COLLIDER_PLANE)
    {
        float3 n = turn(k.rotation, float3(0, 1, 0));
        float before = dot(a - c, n) - r;
        float after = dot(q, n) - r;
        return after < 0 && before >= -r ? hitAt(b - after * n, n) : noHit();
    }
    if (k.kind == COLLIDER_SPHERE)
    {
        return outOf(c, q, k.size.x + r);
    }
    if (k.kind == COLLIDER_CAPSULE)
    {
        float3 axis = turn(k.rotation, float3(0, 1, 0));
        float3 e = axis * clamp(dot(q, axis), -k.size.y, k.size.y);
        return outOf(c + e, q - e, k.size.x + r);
    }
    // a box: out through the face it is least deep behind
    float4 back = float4(-k.rotation.xyz, k.rotation.w);
    float3 l = turn(back, q);
    float3 extent = k.size + r;
    float3 depth = extent - abs(l);
    if (!(depth.x > 0 && depth.y > 0 && depth.z > 0))
    {
        return noHit();
    }
    float3 face = 0;
    if (depth.x <= depth.y && depth.x <= depth.z)
    {
        face.x = l.x < 0 ? -1 : 1;
        l.x = face.x * extent.x;
    }
    else if (depth.y <= depth.z)
    {
        face.y = l.y < 0 ? -1 : 1;
        l.y = face.y * extent.y;
    }
    else
    {
        face.z = l.z < 0 ? -1 : 1;
        l.z = face.z * extent.z;
    }
    return hitAt(c + turn(k.rotation, l), turn(k.rotation, face));
}

/** The first of the frame's colliders in `layers` that the move from `a` to `b` ends touching. */
public ParticleHit hitColliders(ParticleFrame* frame, ParticleEmitter e, uint layers, float r, float3 a,
    float3 b)
{
    ParticleCollider* colliders = frame.colliders;
    uint n = frame.colliderCount;
    for (uint i = 0; i < n; i++)
    {
        ParticleCollider k = colliders[i];
        if ((k.layers & layers) == 0)
        {
            continue;
        }
        precise float3 high = k.positionHigh - e.originHigh;
        precise float3 low = k.positionLow - e.originLow;
        ParticleHit h = hitCollider(k, high + low, r, a, b);
        if (h.hit)
        {
            return h;
        }
    }
    return noHit();
}

/** The height field at grid point (`x`, `z`), clamped to the grid. */
public float heightAt(float* heights, uint n, int x, int z)
{
    return heights[uint(clamp(z, 0, int(n) - 1)) * n + uint(clamp(x, 0, int(n) - 1))];
}

/** Whether the move ends below the height field's ground (with the radius): contact on it, normal from its slope. */
public ParticleHit hitHeight(ParticleFrame* frame, ParticleEmitter e, float r, float3 b)
{
    float* heights = frame.heights;
    uint n = frame.heightResolution;
    if (n < 2)
    {
        return noHit();
    }
    float size = frame.heightSize;
    precise float3 high = e.originHigh - frame.heightCenterHigh;
    precise float3 low = e.originLow - frame.heightCenterLow;
    float3 at = (high + low) + b;
    float spacing = size / float(n - 1);
    float2 grid = (at.xz + 0.5 * size) / spacing;
    if (any(grid < 0) || any(grid > float(n - 1)))
    {
        return noHit();
    }
    int2 cell = min(int2(floor(grid)), int2(int(n) - 2, int(n) - 2));
    float2 t = grid - float2(cell);
    float h00 = heightAt(heights, n, cell.x, cell.y);
    float h10 = heightAt(heights, n, cell.x + 1, cell.y);
    float h01 = heightAt(heights, n, cell.x, cell.y + 1);
    float h11 = heightAt(heights, n, cell.x + 1, cell.y + 1);
    float ground = lerp(lerp(h00, h10, t.x), lerp(h01, h11, t.x), t.y);
    precise float groundY = (ground - e.originHigh.y) - e.originLow.y;
    if (!(groundY > b.y - r))
    {
        return noHit();
    }
    float dx = (lerp(h10, h11, t.y) - lerp(h00, h01, t.y)) / spacing;
    float dz = (lerp(h01, h11, t.x) - lerp(h00, h10, t.x)) / spacing;
    return hitAt(float3(b.x, groundY + r, b.z), normalize(float3(-dx, 1, -dz)));
}

/** The collision view's raw depth (reverse, 0 at infinity) at grid sample (`x`, `y`). */
public float depthAt(float* depth, uint width, uint height, int x, int y)
{
    return depth[DEPTH_HEADER + uint(clamp(y, 0, int(height) - 1)) * width + uint(clamp(x, 0, int(width) - 1))];
}

/** Grid sample (`x`, `y`) of the depth as a point relative to the view's camera. */
public float3 depthPoint(float4x4 inverse, float* depth, uint width, uint height, int x, int y)
{
    float2 ndc = (float2(x, y) + 0.5) / float2(width, height) * 2 - 1;
    float4 p = mul(inverse, float4(ndc, depthAt(depth, width, height, x, y), 1));
    return p.xyz / p.w;
}

/**
 * Whether the move from `a` to `b` crosses the collision view's last depth: in front of it at the start, behind it
 * (by less than `thickness`) at the end; the contact on that surface, the normal from its neighbours.
 */
public ParticleHit hitDepth(ParticleFrame* frame, ParticleEmitter e, float r, float thickness, float3 a,
    float3 b)
{
    if (frame.depthReady == 0)
    {
        return noHit();
    }
    float* depth = frame.depth;
    float4x4 viewProjection;
    float4x4 inverse;
    for (uint i = 0; i < 16; i++)
    {
        viewProjection[i / 4][i % 4] = depth[i];
        inverse[i / 4][i % 4] = depth[16 + i];
    }
    float3 cameraHigh = float3(depth[32], depth[33], depth[34]);
    float3 cameraLow = float3(depth[35], depth[36], depth[37]);
    uint width = uint(depth[38]);
    uint height = uint(depth[39]);
    precise float3 high = e.originHigh - cameraHigh;
    precise float3 low = e.originLow - cameraLow;
    float3 toOrigin = high + low;
    float4 clip = mul(viewProjection, float4(toOrigin + b, 1));
    if (clip.w <= 0)
    {
        return noHit();
    }
    float2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(uv < 0) || any(uv >= 1))
    {
        return noHit();
    }
    int2 cell = int2(uv * float2(width, height));
    float raw = depthAt(depth, width, height, cell.x, cell.y);
    if (raw <= 0)
    {
        return noHit(); // the sky
    }
    float near = mul(viewProjection, float4(0, 0, 0, 1)).z;
    float scene = near / raw;
    float before = mul(viewProjection, float4(toOrigin + a, 1)).w;
    if (!(clip.w + r > scene && clip.w < scene + thickness && before - r <= scene))
    {
        return noHit();
    }
    float3 surface = depthPoint(inverse, depth, width, height, cell.x, cell.y);
    float3 across = depthPoint(inverse, depth, width, height, cell.x + 1, cell.y) - surface;
    float3 up = depthPoint(inverse, depth, width, height, cell.x, cell.y + 1) - surface;
    float3 n = cross(across, up);
    n = dot(n, n) > 1e-12 ? normalize(n) : normalize(-surface);
    n = dot(n, surface) > 0 ? -n : n; // towards the camera
    return hitAt(surface - toOrigin + n * r, n);
}

/** The frame's colliders on nodes in its layers. */
[ParticleModule("collide.colliders")]
[BuiltInTwin]
public struct CollidersCollision
{
    public float fallback = 0;
    public float friction = 0;
    public float layers = 16777215;
    public float radius = 0;
    public float response = 0;
    public float restitution = 0.5;

    public ParticleHit collide(inout ParticleStep s, Particle p)
    {
        return respond(hitColliders(s.frame, s.emitter, uint(layers + 0.5), radiusOf(radius, p), s.from, s.to),
            response, friction, restitution);
    }
};

/** The terrain's height field. */
[ParticleModule("collide.height")]
[BuiltInTwin]
public struct HeightCollision
{
    public float fallback = 0;
    public float friction = 0;
    public float radius = 0;
    public float response = 0;
    public float restitution = 0.5;

    public ParticleHit collide(inout ParticleStep s, Particle p)
    {
        return respond(hitHeight(s.frame, s.emitter, radiusOf(radius, p), s.to), response, friction, restitution);
    }
};

/** The collision view's last depth, as thick as `thickness` behind it. */
[ParticleModule("collide.depth")]
public struct DepthCollision
{
    public float fallback = 0;
    public float friction = 0;
    public float radius = 0;
    public float response = 0;
    public float restitution = 0.5;
    public float thickness = 0.5;

    public ParticleHit collide(inout ParticleStep s, Particle p)
    {
        return respond(hitDepth(s.frame, s.emitter, radiusOf(radius, p), thickness, s.from, s.to), response, friction,
            restitution);
    }
};
