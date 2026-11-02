// Drawing particles in a view (effects.md §2.5): cullMain, a thread per draw, tests each emitter instance's GPU box
// against the view's frustum and writes its indirect draw (its renderer's vertices per instance, as many instances as
// it has particles, or none). Draws of one pipeline next to each other are one multi-draw, each reading its record by
// its draw index. The vertex shaders: billboardMain turns each particle (in the view's sorted order, for sorted
// emitters) into a quad facing the camera's position (a pair of eyes' center), the view plane, along its velocity or
// about its emitter's up axis, with its flipbook frames; ribbonMain draws a segment of the strip through the emitter's
// particles in spawn order (every stride-th, ribbon by ribbon where they interleave), its head at the emitter;
// trailMain a particle's strip through its past positions; beamMain a beam of noisy segments between the emitter and an
// end. Strips face the camera, their width tapering from tail to head, their texture stretched over them or tiled.
// fragmentMain samples the texture (or a round sprite, a soft-edged strip), tests the opaque depth itself, fades softly
// into it by depth or by the view ray's path through the particle's sphere, and writes premultiplied color: alpha
// blended, additive, or between them (alpha to add).
import particle;
import meshlets.common;
import bindless;

struct ParticleDrawPush
{
    Particle* particles;           // the half of the pool the simulation wrote this frame
    ParticleEmitter* emitters;     // the frame's list
    View* view;                    // the view drawn
    uint* words;                   // the program buffer: the renderers' records
    uint* state;                   // the emitter instances' state
    ParticleDrawRecord* draws;     // this view's draws
    uint* commands;                // their indirect draws (cullMain)
    uint4* side;                   // the side arrays' half written this frame: trails
    uint* order;                   // ribbons of several strands: each range in ribbon order
    uint2* sorted;                 // the view's sort: each sorted emitter's keys and indices, farthest first
    uint* sortArgs;                // and from word 4 on, each sorted emitter's first key in it
    float3 facing;                 // what billboards and strips face, from the camera: a pair's center, or 0
    uint drawCount;                // (cullMain)
    uint first;                    // the first draw of the multi-draw recorded: each reads its record by draw index
    uint depth;                    // the scene's depth, sampled slot: full resolution, or the half's farthest
    uint flags;                    // DRAW_PAIRED, and GLASS_* << 1: split emitters keep their fragments behind or before
    uint glassDepth;               // the nearest blended surfaces' depth, sampled slot (split emitters), or NO_TEXTURE
};

/** Of an eye of a pair: view-plane billboards face the center instead. */
static const uint DRAW_PAIRED = 1;

/** Of a pass drawing split emitters: the fragments behind the nearest glass (drawn before it), or in front of it. */
static const uint GLASS_BEHIND = 1;
static const uint GLASS_FRONT = 2;

/** The salt of the hash a particle compares with its emitter's drawn fraction (levels of detail). */
static const uint LOD_SALT = 0x4c0d;
/** The hashes beyond the drawn fraction over which particles fade out, rather than vanish. */
static const float LOD_BAND = 0.08;

/**
 * How much of particle `p` its emitter draws: 1 within its drawn fraction by its hash, fading to 0 beyond it across
 * LOD_BAND; times the emitter's fade (a placement's share of its variant).
 */
float kept(ParticleEmitter e, Particle p)
{
    float keep = e.drawn >= 1 ? 1 : saturate(1 + (e.drawn - random(p.seed, LOD_SALT)) / LOD_BAND);
    return keep * e.fade;
}

/** Where particle `p` is drawn: moved on by its velocity for the time its emitter has been held (reduced rates). */
float3 drawnAt(ParticleEmitter e, Particle p)
{
    return p.position + p.velocity * e.held;
}

[[vk::push_constant]]
ConstantBuffer<ParticleDrawPush> push;

/** A renderer's record. */
ParticleRenderer rendererAt(uint word)
{
    uint* words = push.words;
    ParticleRenderer* r = (ParticleRenderer*) (words + word);
    return r[0];
}

[shader("compute")]
[numthreads(64, 1, 1)]
void cullMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.drawCount)
    {
        return;
    }
    ParticleDrawRecord* draws = push.draws;
    ParticleEmitter* emitters = push.emitters;
    uint* state = push.state;
    View* view = push.view;
    ParticleDrawRecord d = draws[id.x];
    ParticleEmitter e = emitters[d.emitter];
    ParticleRenderer r = rendererAt(d.renderer);
    uint at = e.instance * STATE_WORDS;
    uint alive = state[at + STATE_ALIVE];
    uint vertices = r.kind == RENDER_TRAIL ? 6 * r.points : r.kind == RENDER_BEAM ? 6 * r.segments : 6;
    uint instances = r.kind == RENDER_BEAM ? 1 : alive;
    bool visible = (e.flags & EMITTER_HIDDEN) == 0 && (r.kind == RENDER_BEAM
        || alive > 0 && state[at + STATE_BOUNDS] <= state[at + STATE_BOUNDS + 3]);
    if (visible && r.kind != RENDER_BEAM)
    {
        float3 low;
        float3 high;
        stateBounds(state, e.instance, low, high);
        float3 center = relativeTo(e, 0.5 * (low + high), view.position, view.positionLow);
        float radius = 0.5 * length(high - low) * e.scale;
        if (r.kind == RENDER_RIBBON)
        {
            // its head reaches the emitter, outside its particles' box
            float3 head = relativeTo(e, (e.flags & EMITTER_LOCAL) != 0 ? 0 : e.position, view.position,
                view.positionLow);
            radius = max(radius, length(head - center));
        }
        for (uint i = 0; i < 6 && visible; i++)
        {
            float4 plane = view.planes[i];
            visible = dot(plane.xyz, center) + plane.w >= -radius;
        }
    }
    uint* commands = push.commands;
    commands[4 * id.x] = vertices;
    commands[4 * id.x + 1] = visible ? instances : 0;
    commands[4 * id.x + 2] = 0;
    commands[4 * id.x + 3] = 0;
}

struct ParticleVertex
{
    float4 position : SV_Position;
    float4 color : COLOR;          // straight alpha
    float2 uv : TEXCOORD0;         // across the quad (strips: u along, v across)
    float3 sphere : TEXCOORD1;     // the particle's view distance, its radius, and the fragment's own view distance
    nointerpolation uint4 frames : TEXCOORD2; // flipbook frames, their blend as 16-bit fixed point, and the kind
    nointerpolation uint4 draw : TEXCOORD3;   // its draw's renderer word, texture slot, second texture, LIGHTING_*
    float3 world : TEXCOORD4;      // relative to the camera: per-pixel lighting
    nointerpolation float3 lit : TEXCOORD5;   // per-vertex lighting: the light at its center (else 1)
    nointerpolation float3 right : TEXCOORD6; // billboards' frame: right, up, and towards the camera
    nointerpolation float3 up : TEXCOORD7;
    nointerpolation float3 forward : TEXCOORD8;
};

/** The record of draw `index` of the multi-draw recorded. */
ParticleDrawRecord drawAt(uint index)
{
    ParticleDrawRecord* draws = push.draws;
    return draws[push.first + index];
}

/** Row `r` of a view-projection matrix, without its last column: the camera's axes, scaled. */
float3 row(float4x4 m, uint r)
{
    return float3(mul(m, float4(1, 0, 0, 0))[r], mul(m, float4(0, 1, 0, 0))[r], mul(m, float4(0, 0, 1, 0))[r]);
}

/** A vertex drawing nothing: outside the clip volume. */
ParticleVertex nothing()
{
    ParticleVertex o;
    o.position = float4(0, 0, 2, 1);
    o.color = 0;
    o.uv = 0;
    o.sphere = 0;
    o.frames = 0;
    o.draw = 0;
    o.world = 0;
    o.lit = 1;
    o.right = 0;
    o.up = 0;
    o.forward = 0;
    return o;
}

// ---------------------------------------------------------------------------------------------- lighting (E14)

/** The words of the commands buffer before each slot's light: the view's indirect draws. */
static const uint LIT_WORDS = 4 * MAX_DRAWS;

/** The weights of a light from `l` (in the billboard's frame: right, up, towards the camera) for six-way maps. */
float sixWay(float3 l, float4 a, float4 b)
{
    float3 w = l * l; // the unit direction's squared components share it out
    return (l.x >= 0 ? a.r : b.r) * w.x + (l.y >= 0 ? a.g : b.g) * w.y + (l.z >= 0 ? b.b : a.b) * w.z;
}

/**
 * The light a particle reflects at `world` (relative to the camera; at `pixel`, `depth` from the viewer): every light
 * of the view's list there after its shadow (the sun without a list), and the sky, as `mode` weighs them: 0 the same
 * from every side (a point lit per vertex), 1 by `normal` wrapped around (lit per pixel), 2 by six-way maps `a` and
 * `b` in the frame (`right`, `up`, `normal` towards the camera). Diffuse, over pi.
 */
float3 particleLight(LightListHeader* h, float3 world, float3 normal, float3 right, float3 up, uint mode, float4 a,
    float4 b, uint2 pixel, float depth)
{
    float sky = mode == 2 ? (a.r + a.g + a.b + b.r + b.g + b.b) / 6 : 1;
    float3 sum = SKY_IRRADIANCE * sky;
    if (h == nullptr)
    {
        float3 l = float3(dot(SUN, right), dot(SUN, up), dot(SUN, normal));
        sum += SUN_IRRADIANCE * (mode == 0 ? 1 : mode == 1 ? saturate(0.5 + 0.5 * dot(normal, SUN)) : sixWay(l, a, b));
        return sum * 0.31830988;
    }
    LightListHeader header = loadHeader(h);
    ClusterLightList list = ClusterLightList(header, pixel, depth);
    for (uint i = 0; i < list.count(); i++)
    {
        float3 toLight;
        float3 e = lightIrradiance(NoRays(), header, list.light(i), world, normal, pixel, depth, toLight);
        float w = 1;
        if (mode == 1)
        {
            w = saturate(0.5 + 0.5 * dot(normal, toLight));
        }
        else if (mode == 2)
        {
            w = sixWay(float3(dot(toLight, right), dot(toLight, up), dot(toLight, normal)), a, b);
        }
        sum += e * w;
    }
    return sum * 0.31830988; // over pi
}

/** The pixel and view depth of `p`, relative to the camera, in `view`: where its light clusters are looked up. */
void pixelOf(View* view, float3 p, out uint2 pixel, out float depth)
{
    float4 clip = mul(view.viewProjection, float4(p, 1));
    depth = clip.w;
    float2 ndc = clip.xy / max(abs(clip.w), 1e-6);
    uint2 size = view.outputSize;
    pixel = uint2(clamp((ndc * 0.5 + 0.5) * float2(size), 0.0, float2(size) - 1));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightMain(uint3 id : SV_DispatchThreadID, uint3 group : SV_GroupID)
{
    // Per-vertex lighting (E14): a row of groups per draw, a thread per particle of those lit per vertex: the light at
    // its center, from every side, into its slot's four words after the view's indirect draws
    ParticleDrawRecord* draws = push.draws;
    ParticleDrawRecord d = draws[group.y];
    if (d.lighting != LIGHTING_VERTEX)
    {
        return;
    }
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[d.emitter];
    uint* state = push.state;
    uint k = id.x;
    if (k >= state[e.instance * STATE_WORDS + STATE_ALIVE])
    {
        return;
    }
    Particle* particles = push.particles;
    Particle p = particles[e.first + k];
    View* view = push.view;
    float3 center = relativeTo(e, p.position, view.position, view.positionLow);
    uint2 pixel;
    float depth;
    pixelOf(view, center, pixel, depth);
    float3 toCamera = normalize(push.facing - center);
    float3 lit = particleLight((LightListHeader*) d.lights, center, toCamera, 0, 0, 0, 0, 0, pixel, depth);
    uint* commands = push.commands;
    uint at = LIT_WORDS + 4 * (e.first + k);
    commands[at] = asuint(lit.x);
    commands[at + 1] = asuint(lit.y);
    commands[at + 2] = asuint(lit.z);
}

/** Corner `vertex % 6` of two triangles: (-1, -1), (1, -1), (-1, 1), (1, -1), (1, 1), (-1, 1). */
float2 corner(uint vertex)
{
    uint c = vertex % 6;
    return float2(c == 1 || c == 3 || c == 4 ? 1 : -1, c == 2 || c >= 4 ? 1 : -1);
}

[shader("vertex")]
ParticleVertex billboardMain(uint vertex : SV_VertexID, uint instance : SV_InstanceID, uint drawIndex : SV_DrawIndex)
{
    ParticleVertex o;
    ParticleDrawRecord d = drawAt(drawIndex);
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[d.emitter];
    uint index = instance;
    if (d.sort != NO_SORT)
    {
        // Back to front: the emitter's run of the sort (its dead particles draw nothing, as unsorted)
        uint* sortArgs = push.sortArgs;
        uint2* sorted = push.sorted;
        uint key = sortArgs[4 + d.sort] + instance;
        if (key >= sortArgs[3])
        {
            return nothing(); // left out of a sort its buffers had no room for
        }
        index = min(sorted[key].y, e.capacity - 1);
    }
    Particle* particles = push.particles;
    Particle p = particles[e.first + index];
    View* view = push.view;
    float4x4 viewProjection = view.viewProjection;
    if ((p.flags & PARTICLE_DEAD) != 0 || p.age >= p.lifetime)
    {
        return nothing();
    }
    float keep = kept(e, p);
    if (!(keep > 0))
    {
        return nothing();
    }
    ParticleRenderer r = rendererAt(d.renderer);
    float3 center = relativeTo(e, drawnAt(e, p), view.position, view.positionLow);
    float3 velocity = (e.flags & EMITTER_LOCAL) != 0 ? turn(e.rotation, p.velocity) : p.velocity;
    // Towards the camera, or for both eyes of a pair their center: one orientation, no rivalry between the eyes
    float3 toCamera = normalize(push.facing - center);
    float3 right;
    float3 up;
    // Placements scale; levels of detail grow the kept to keep the coverage
    float2 half = 0.5 * p.size * e.scale * (e.drawn < 1 ? min(rsqrt(max(e.drawn, 0.05)), 2.0) : 1.0);
    if (r.facing == FACING_VIEW_PLANE && (push.flags & DRAW_PAIRED) == 0)
    {
        right = normalize(row(viewProjection, 0));
        up = -normalize(row(viewProjection, 1));
    }
    else if (r.facing == FACING_VELOCITY || r.facing == FACING_AXIS)
    {
        float speed = length(velocity);
        float3 axis = r.facing == FACING_AXIS ? turn(e.rotation, float3(0, 1, 0))
            : speed > 1e-6 ? velocity / speed : float3(0, 1, 0);
        float3 side = cross(axis, toCamera);
        right = dot(side, side) > 1e-12 ? normalize(side) : normalize(cross(axis, float3(0.577, 0.577, 0.577)));
        up = axis;
        if (r.facing == FACING_VELOCITY)
        {
            half.y *= 1 + r.stretch * speed;
        }
    }
    else // FACING_POSITION (and FREE, until particles have normals)
    {
        float3 side = cross(float3(0, 1, 0), toCamera);
        right = dot(side, side) > 1e-12 ? normalize(side) : float3(1, 0, 0);
        up = cross(toCamera, right);
    }
    if (r.facing == FACING_POSITION || r.facing == FACING_VIEW_PLANE)
    {
        float c = cos(p.rotation);
        float s = sin(p.rotation);
        float3 turned = c * right + s * up;
        up = c * up - s * right;
        right = turned;
    }
    float2 xy = corner(vertex);
    float3 world = center + right * (xy.x * half.x) + up * (xy.y * half.y);
    o.position = mul(viewProjection, float4(world, 1));
    o.color = float4(p.color.rgb, p.color.a * keep);
    o.uv = float2(0.5 + 0.5 * xy.x, 0.5 - 0.5 * xy.y);
    float centerW = mul(viewProjection, float4(center, 1)).w;
    o.sphere = float3(centerW, max(half.x, half.y), o.position.w);
    // Flipbook frames: by age, looping, or over the particle's life.
    float frames = r.frames;
    float t = (r.rate > 0 ? p.age * r.rate : p.age / p.lifetime * frames) + p.custom.x * frames; // its start
    bool loop = (r.flags & DRAW_LOOP) != 0;
    t = loop || p.custom.x != 0 ? t - frames * floor(t / frames) : clamp(t, 0.0, frames - 1);
    uint f0 = min(uint(t), uint(frames) - 1);
    uint f1 = loop ? (f0 + 1) % uint(frames) : min(f0 + 1, uint(frames) - 1);
    float blend = (r.flags & DRAW_BLEND_FRAMES) != 0 ? t - floor(t) : 0;
    o.frames = uint4(f0, f1, uint(blend * 65535), RENDER_BILLBOARD);
    o.draw = uint4(d.renderer, d.texture, d.extra, d.lighting);
    o.world = world;
    o.right = right;
    o.up = up;
    o.forward = toCamera;
    o.lit = 1;
    if (d.lighting == LIGHTING_VERTEX)
    {
        uint* commands = push.commands;
        uint at = LIT_WORDS + 4 * (e.first + index);
        o.lit = float3(asfloat(commands[at]), asfloat(commands[at + 1]), asfloat(commands[at + 2]));
    }
    return o;
}

/** A point of a strip: where it is relative to the camera, its width, color and texture coordinate along it. */
struct StripPoint
{
    float3 position;
    float width;
    float4 color;
    float u;
};

/**
 * Vertex `vertex` (0 to 5) of the strip segment from `a` to `b`, `before` the point before `a` and `after` the one
 * after `b` (the same point where there is none): each end turned to face the camera across its tangent, so
 * neighbouring segments meet.
 */
ParticleVertex segment(StripPoint before, StripPoint a, StripPoint b, StripPoint after, uint vertex, uint kind,
    ParticleDrawRecord d)
{
    float2 xy = corner(vertex);
    bool atB = xy.x > 0;
    StripPoint p = atB ? b : a;
    float3 tangent = atB ? after.position - a.position : b.position - before.position;
    float3 side = cross(tangent, push.facing - p.position);
    side = dot(side, side) > 1e-20 ? normalize(side) : float3(0, 1, 0);
    float3 world = p.position + side * (0.5 * p.width * xy.y);
    View* view = push.view;
    float4x4 viewProjection = view.viewProjection;
    ParticleVertex o;
    o.position = mul(viewProjection, float4(world, 1));
    o.color = p.color;
    o.uv = float2(p.u, 0.5 - 0.5 * xy.y);
    o.sphere = float3(o.position.w, 0.5 * p.width, o.position.w);
    o.frames = uint4(0, 0, 0, kind);
    o.draw = uint4(d.renderer, d.texture, NO_TEXTURE, LIGHTING_UNLIT);
    o.world = world;
    o.lit = 1;
    o.right = 0;
    o.up = 0;
    o.forward = 0;
    return o;
}

/** Particle `i` of the emitter's range as a strip point: `along` (0 tail, 1 head) tapers its width. */
StripPoint stripPoint(ParticleEmitter e, ParticleRenderer r, uint i, float along, float u)
{
    Particle* particles = push.particles;
    Particle p = particles[e.first + i];
    View* view = push.view;
    StripPoint s;
    s.position = relativeTo(e, drawnAt(e, p), view.position, view.positionLow);
    s.width = p.size.x * e.scale * lerp(r.widthTail, r.widthHead, along);
    s.color = float4(p.color.rgb, p.color.a * e.fade);
    s.u = u;
    return s;
}

/** Particle `p`'s ribbon: its spawn index's strand of a renderer's several, or its parent's (`ribbons` 0, per parent). */
uint ribbonOf(Particle p, ParticleRenderer r)
{
    uint id = asuint(p.custom.y);
    return r.ribbons == 0 ? id : id % r.ribbons;
}

[shader("vertex")]
ParticleVertex ribbonMain(uint vertex : SV_VertexID, uint index : SV_InstanceID, uint drawIndex : SV_DrawIndex)
{
    ParticleDrawRecord d = drawAt(drawIndex);
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[d.emitter];
    ParticleRenderer r = rendererAt(d.renderer);
    uint* state = push.state;
    uint n = state[e.instance * STATE_WORDS + STATE_ALIVE];
    Particle* particles = push.particles;
    View* view = push.view;
    if (r.ribbons != 1)
    {
        // Ribbon by ribbon (second key): neighbours in the order buffer of one ribbon make a segment
        uint* order = push.order;
        if (index + 1 >= n)
        {
            return nothing();
        }
        uint i0 = order[e.first + index];
        uint i1 = order[e.first + index + 1];
        uint ribbon = ribbonOf(particles[e.first + i0], r);
        if (ribbonOf(particles[e.first + i1], r) != ribbon)
        {
            return nothing();
        }
        uint ib = index > 0 ? order[e.first + index - 1] : i0;
        uint ia = index + 2 < n ? order[e.first + index + 2] : i1;
        ib = ribbonOf(particles[e.first + ib], r) == ribbon ? ib : i0;
        ia = ribbonOf(particles[e.first + ia], r) == ribbon ? ia : i1;
        // Along each ribbon by age: the tail the oldest
        Particle p0 = particles[e.first + i0];
        Particle p1 = particles[e.first + i1];
        float along0 = saturate(1 - p0.age / p0.lifetime);
        float along1 = saturate(1 - p1.age / p1.lifetime);
        StripPoint a = stripPoint(e, r, i0, along0, r.tile > 0 ? p0.spawnIndex / r.tile : along0);
        StripPoint b = stripPoint(e, r, i1, along1, r.tile > 0 ? p1.spawnIndex / r.tile : along1);
        return segment(stripPoint(e, r, ib, 0, 0), a, b, stripPoint(e, r, ia, 0, 0), vertex, RENDER_RIBBON, d);
    }
    // One ribbon: every stride-th particle by spawn index, then its head at the emitter (no first-frame gap).
    uint stride = max(r.stride, 1u);
    if (r.lodDistance > 0)
    {
        float distance = length(relativeTo(e, e.position, view.position, view.positionLow));
        stride *= uint(exp2(floor(log2(max(distance / r.lodDistance, 1.0)))));
    }
    if (n == 0)
    {
        return nothing();
    }
    uint start = (stride - particles[e.first].spawnIndex % stride) % stride;
    uint count = start < n ? (n - 1 - start) / stride + 1 : 0; // points from the particles
    bool head = (e.flags & EMITTER_CPU) == 0;
    uint points = count + (head ? 1 : 0);
    if (index + 1 >= points)
    {
        return nothing();
    }
    float scale = 1.0 / max(float(points - 1), 1.0);
    StripPoint p[4];
    for (int k = -1; k <= 2; k++)
    {
        int j = clamp(int(index) + k, 0, int(points) - 1);
        float along = j * scale;
        if (uint(j) < count)
        {
            uint i = start + uint(j) * stride;
            float u = r.tile > 0 ? particles[e.first + i].spawnIndex / r.tile : along;
            p[k + 1] = stripPoint(e, r, i, along, u);
        }
        else
        {
            // the head: the emitter now, the youngest particle's size and color
            uint youngest = start + (count - 1) * stride;
            p[k + 1] = stripPoint(e, r, youngest, 1, r.tile > 0 ? (particles[e.first + youngest].spawnIndex + stride)
                / r.tile : 1);
            p[k + 1].position = relativeTo(e, (e.flags & EMITTER_LOCAL) != 0 ? 0 : e.position, view.position,
                view.positionLow);
        }
    }
    return segment(p[0], p[1], p[2], p[3], vertex, RENDER_RIBBON, d);
}

[shader("vertex")]
ParticleVertex trailMain(uint vertex : SV_VertexID, uint index : SV_InstanceID, uint drawIndex : SV_DrawIndex)
{
    ParticleDrawRecord d = drawAt(drawIndex);
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[d.emitter];
    ParticleRenderer r = rendererAt(d.renderer);
    Particle* particles = push.particles;
    Particle p = particles[e.first + index];
    if ((p.flags & PARTICLE_DEAD) != 0 || p.age >= p.lifetime)
    {
        return nothing();
    }
    // Its ring of past positions, oldest first, then where it is now.
    uint* words = push.words;
    uint header = words[e.program];
    uint4* side = push.side;
    uint4* trail = side + e.side + index * e.sideUnits + (((header >> 16) & PROGRAM_OVER) != 0 ? 2 : 0)
        + (((header >> 16) & PROGRAM_EMIT) != 0 ? 1 : 0);
    uint n = r.points;
    uint head = trail[0].x;
    uint s = vertex / 6;
    View* view = push.view;
    StripPoint q[4];
    for (int k = -1; k <= 2; k++)
    {
        uint j = uint(clamp(int(s) + k, 0, int(n)));
        float along = float(j) / float(n);
        StripPoint sp;
        float3 at = j == n ? p.position : asfloat(trail[1 + (head + 1 + j) % n].xyz);
        sp.position = relativeTo(e, at, view.position, view.positionLow);
        sp.width = p.size.x * lerp(r.widthTail, r.widthHead, along);
        sp.color = p.color;
        sp.u = r.tile > 0 ? float(j) / r.tile : along;
        q[k + 1] = sp;
    }
    return segment(q[0], q[1], q[2], q[3], vertex, RENDER_TRAIL, d);
}

/** A smooth value in about [-1, 1] along a beam at `t`, moving with time. */
float wave(float t, float time, float phase)
{
    return 0.6 * sin(17.0 * t + 5.0 * time + phase) + 0.4 * sin(41.0 * t - 7.0 * time + 2.0 * phase);
}

[shader("vertex")]
ParticleVertex beamMain(uint vertex : SV_VertexID, uint drawIndex : SV_DrawIndex)
{
    ParticleDrawRecord d = drawAt(drawIndex);
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[d.emitter];
    ParticleRenderer r = rendererAt(d.renderer);
    View* view = push.view;
    float3 start = relativeTo(e, (e.flags & EMITTER_LOCAL) != 0 ? 0 : e.position, view.position, view.positionLow);
    float3 end = d.end;
    float3 direction = end - start;
    float3 side = cross(direction, push.facing - start);
    side = dot(side, side) > 1e-20 ? normalize(side) : float3(0, 1, 0);
    uint m = max(r.segments, 1u);
    uint s = vertex / 6;
    StripPoint q[4];
    for (int k = -1; k <= 2; k++)
    {
        float t = float(clamp(int(s) + k, 0, int(m))) / float(m);
        StripPoint sp;
        sp.position = start + direction * t + side * (r.noise * sin(3.14159265 * t) * wave(t, d.time, 0));
        sp.width = r.width * lerp(r.widthTail, r.widthHead, t);
        sp.color = r.color;
        sp.u = r.tile > 0 ? t * length(direction) / r.tile : t;
        q[k + 1] = sp;
    }
    return segment(q[0], q[1], q[2], q[3], vertex, RENDER_BEAM, d);
}

/** Frame `f` of the flipbook in texture slot `texture` at `uv` within it. */
float4 frame(ParticleRenderer r, uint texture, uint f, float2 uv)
{
    uint columns = max(r.columns, 1u);
    uint rows = max(r.rows, 1u);
    float2 cell = float2(f % columns, f / columns);
    return textures[NonUniformResourceIndex(texture)].Sample(samplers[LINEAR_CLAMP],
        (cell + uv) / float2(columns, rows));
}

/** The view's light list, as fragment `v`'s draw names it. */
LightListHeader* lightsOf(ParticleVertex v)
{
    ParticleDrawRecord* draws = push.draws;
    return (LightListHeader*) draws[push.first].lights; // the same for every draw of the view
}

[shader("fragment")]
float4 fragmentMain(ParticleVertex v) : SV_Target
{
    return shade(v);
}

/**
 * The reactive mask (TAA hooks, effects step 21): how much particles cover each pixel, their alpha and their added
 * light's brightness, kept at the greatest (blended by max): temporal antialiasing trusts its history less there.
 */
[shader("fragment")]
float4 reactiveMain(ParticleVertex v) : SV_Target
{
    float4 c = shade(v);
    float cover = saturate(max(c.a, max(c.r, max(c.g, c.b))));
    return float4(cover, cover, cover, cover);
}

/**
 * The overdraw heat map (a debug view, effects step 18): each layer of particles adds a dim orange where it covers
 * anything, so the scene glows by how many layers are drawn over each pixel: about ten to white.
 */
[shader("fragment")]
float4 overdrawMain(ParticleVertex v) : SV_Target
{
    float4 c = shade(v);
    return c.a > 0.004 || any(c.rgb > 0.004) ? float4(0.1, 0.05, 0.02, 0) : 0;
}

/** Fragment `v`'s premultiplied color: its texture, lighting, the opaque scene's depth test and soft fade. */
float4 shade(ParticleVertex v)
{
    ParticleRenderer r = rendererAt(v.draw.x);
    uint texture = v.draw.y;
    bool strip = v.frames.w != RENDER_BILLBOARD;
    float4 texel;
    if (texture == NO_TEXTURE)
    {
        // a round sprite, or a strip soft at its edges
        float2 d = strip ? float2(0, 2 * v.uv.y - 1) : 2 * v.uv - 1;
        texel = float4(1, 1, 1, saturate(1 - dot(d, d)));
    }
    else if (strip)
    {
        texel = textures[NonUniformResourceIndex(texture)].Sample(samplers[LINEAR_REPEAT], v.uv);
    }
    else
    {
        texel = frame(r, texture, v.frames.x, v.uv);
        if (v.frames.z != 0)
        {
            texel = lerp(texel, frame(r, texture, v.frames.y, v.uv), float(v.frames.z) / 65535.0);
        }
    }
    float4 color = v.color * texel;
    uint lighting = v.draw.w;
    if (lighting == LIGHTING_VERTEX)
    {
        color.rgb *= v.lit;
    }
    else if (lighting == LIGHTING_PIXEL || lighting == LIGHTING_SIX_WAY)
    {
        uint2 pixel = uint2(v.position.xy);
        float depth = v.position.w;
        float3 n = v.forward;
        if (lighting == LIGHTING_PIXEL)
        {
            // Its normal map's (tangent space: right, up, towards the camera), else a sphere's: a round puff
            float2 d = 2 * v.uv - 1;
            float3 m = float3(d.x, -d.y, sqrt(saturate(1 - dot(d, d))));
            if (v.draw.z != NO_TEXTURE) // branches, not selects: no sample of a slot that is not there
            {
                m = frame(r, v.draw.z, v.frames.x, v.uv).xyz * 2 - 1;
            }
            n = normalize(v.right * m.x + v.up * m.y + v.forward * m.z);
            color.rgb *= particleLight(lightsOf(v), v.world, n, v.right, v.up, 1, 0, 0, pixel, depth);
        }
        else
        {
            // Six-way maps: the texture's right, top, back and alpha, the second's left, bottom, front and emission
            float4 b = float4(texel.rgb, 0);
            if (v.draw.z != NO_TEXTURE)
            {
                b = frame(r, v.draw.z, v.frames.x, v.uv);
            }
            float4 a = texel;
            color = float4(v.color.rgb * (particleLight(lightsOf(v), v.world, n, v.right, v.up, 2, a, b, pixel, depth)
                + b.a),
                v.color.a * a.a);
        }
    }
    // The opaque scene's distance here: reverse depth with an infinite far plane, depth = near / distance.
    View* view = push.view;
    float near = mul(view.viewProjection, float4(0, 0, 0, 1)).z;
    float depth = textures[push.depth].Load(int3(int2(v.position.xy), 0)).r;
    float scene = depth > 0 ? near / depth : 1e30;
    float distance = v.sphere.z;
    if (distance > scene)
    {
        discard;
    }
    uint glassSide = push.flags >> 1;
    if (glassSide != 0)
    {
        // Split at the nearest glass: behind it drawn before the glass (tinted by it), in front of it after
        float glass = textures[push.glassDepth].Load(int3(int2(v.position.xy), 0)).r;
        bool behind = glass > 0 && distance > near / glass;
        if (behind != (glassSide == GLASS_BEHIND))
        {
            discard;
        }
    }
    if (r.soft > 0)
    {
        if ((r.flags & DRAW_VOLUME) != 0 && !strip)
        {
            // The view ray's path through the particle's sphere that lies before the opaque scene, of its diameter.
            float2 d = 2 * v.uv - 1;
            float radius = v.sphere.y;
            float through = 2 * radius * sqrt(saturate(1 - dot(d, d)));
            float front = v.sphere.x - 0.5 * through;
            color.a *= saturate((min(scene, front + through) - front) / max(2 * radius, 1e-6));
        }
        else
        {
            color.a *= saturate((scene - distance) / r.soft);
        }
    }
    return float4(color.rgb * color.a, color.a * (1 - r.alphaToAdd));
}
