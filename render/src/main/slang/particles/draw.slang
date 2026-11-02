// Drawing particles in a view (effects.md §2.5): cullMain, a thread per draw, tests each emitter instance's GPU box
// against the view's frustum and writes its indirect draw (its renderer's vertices per instance, as many instances as
// it has particles, or none). The vertex shaders: billboardMain turns each particle into a quad facing the camera's
// position, the view plane, along its velocity or about its emitter's up axis, with its flipbook frames; ribbonMain
// draws a segment of the strip through the emitter's particles in spawn order (every stride-th, ribbon by ribbon where
// they interleave), its head at the emitter; trailMain a particle's strip through its past positions; beamMain a beam
// of noisy segments between the emitter and an end. Strips face the camera, their width tapering from tail to head,
// their texture stretched over them or tiled. fragmentMain samples the texture (or a round sprite, a soft-edged strip),
// tests the opaque depth itself, fades softly into it by depth or by the view ray's path through the particle's
// sphere, and writes premultiplied color: alpha blended, additive, or between them (alpha to add).
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
    ParticleDrawRecord* draws;     // this view's draws (cullMain)
    uint* commands;                // their indirect draws (cullMain)
    uint4* side;                   // the side arrays' half written this frame: trails
    uint* order;                   // ribbons of several strands: each range in ribbon order
    uint drawCount;                // (cullMain)
    uint draw;                     // the draw recorded: its place in the list
    uint emitter;                  // its emitter's place in the frame's list
    uint renderer;                 // its renderer's word
    uint texture;                  // its texture's sampled slot, or NO_TEXTURE
    uint depth;                    // the scene's depth, sampled slot
    float3 end;                    // beams: the end, relative to the camera
    float time;                    // the effect's time, in seconds: beams' noise
};

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
    bool visible = r.kind == RENDER_BEAM
        || alive > 0 && state[at + STATE_BOUNDS] <= state[at + STATE_BOUNDS + 3];
    if (visible && r.kind != RENDER_BEAM)
    {
        float3 low;
        float3 high;
        stateBounds(state, e.instance, low, high);
        float3 center = relativeTo(e, 0.5 * (low + high), view.position, view.positionLow);
        float radius = 0.5 * length(high - low);
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
};

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
    return o;
}

/** Corner `vertex % 6` of two triangles: (-1, -1), (1, -1), (-1, 1), (1, -1), (1, 1), (-1, 1). */
float2 corner(uint vertex)
{
    uint c = vertex % 6;
    return float2(c == 1 || c == 3 || c == 4 ? 1 : -1, c == 2 || c >= 4 ? 1 : -1);
}

[shader("vertex")]
ParticleVertex billboardMain(uint vertex : SV_VertexID, uint index : SV_InstanceID)
{
    ParticleVertex o;
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[push.emitter];
    Particle* particles = push.particles;
    Particle p = particles[e.first + index];
    View* view = push.view;
    float4x4 viewProjection = view.viewProjection;
    if ((p.flags & PARTICLE_DEAD) != 0 || p.age >= p.lifetime)
    {
        return nothing();
    }
    ParticleRenderer r = rendererAt(push.renderer);
    float3 center = relativeTo(e, p.position, view.position, view.positionLow);
    float3 velocity = (e.flags & EMITTER_LOCAL) != 0 ? turn(e.rotation, p.velocity) : p.velocity;
    float3 toCamera = normalize(-center);
    float3 right;
    float3 up;
    float2 half = 0.5 * p.size;
    if (r.facing == FACING_VIEW_PLANE)
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
    o.color = p.color;
    o.uv = float2(0.5 + 0.5 * xy.x, 0.5 - 0.5 * xy.y);
    float centerW = mul(viewProjection, float4(center, 1)).w;
    o.sphere = float3(centerW, max(half.x, half.y), o.position.w);
    // Flipbook frames: by age, looping, or over the particle's life.
    float frames = r.frames;
    float t = r.rate > 0 ? p.age * r.rate : p.age / p.lifetime * frames;
    bool loop = (r.flags & DRAW_LOOP) != 0;
    t = loop ? t - frames * floor(t / frames) : clamp(t, 0.0, frames - 1);
    uint f0 = min(uint(t), uint(frames) - 1);
    uint f1 = loop ? (f0 + 1) % uint(frames) : min(f0 + 1, uint(frames) - 1);
    float blend = (r.flags & DRAW_BLEND_FRAMES) != 0 ? t - floor(t) : 0;
    o.frames = uint4(f0, f1, uint(blend * 65535), RENDER_BILLBOARD);
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
ParticleVertex segment(StripPoint before, StripPoint a, StripPoint b, StripPoint after, uint vertex, uint kind)
{
    float2 xy = corner(vertex);
    bool atB = xy.x > 0;
    StripPoint p = atB ? b : a;
    float3 tangent = atB ? after.position - a.position : b.position - before.position;
    float3 side = cross(tangent, -p.position);
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
    return o;
}

/** Particle `i` of the emitter's range as a strip point: `along` (0 tail, 1 head) tapers its width. */
StripPoint stripPoint(ParticleEmitter e, ParticleRenderer r, uint i, float along, float u)
{
    Particle* particles = push.particles;
    Particle p = particles[e.first + i];
    View* view = push.view;
    StripPoint s;
    s.position = relativeTo(e, p.position, view.position, view.positionLow);
    s.width = p.size.x * lerp(r.widthTail, r.widthHead, along);
    s.color = p.color;
    s.u = u;
    return s;
}

[shader("vertex")]
ParticleVertex ribbonMain(uint vertex : SV_VertexID, uint index : SV_InstanceID)
{
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[push.emitter];
    ParticleRenderer r = rendererAt(push.renderer);
    uint* state = push.state;
    uint n = state[e.instance * STATE_WORDS + STATE_ALIVE];
    Particle* particles = push.particles;
    View* view = push.view;
    if (r.ribbons > 1)
    {
        // Ribbon by ribbon (second key): neighbours in the order buffer of one ribbon make a segment
        uint* order = push.order;
        if (index + 1 >= n)
        {
            return nothing();
        }
        uint i0 = order[e.first + index];
        uint i1 = order[e.first + index + 1];
        uint ribbon = particles[e.first + i0].spawnIndex % r.ribbons;
        if (particles[e.first + i1].spawnIndex % r.ribbons != ribbon)
        {
            return nothing();
        }
        uint ib = index > 0 ? order[e.first + index - 1] : i0;
        uint ia = index + 2 < n ? order[e.first + index + 2] : i1;
        ib = particles[e.first + ib].spawnIndex % r.ribbons == ribbon ? ib : i0;
        ia = particles[e.first + ia].spawnIndex % r.ribbons == ribbon ? ia : i1;
        float scale = 1.0 / max(float(n), 1.0);
        StripPoint a = stripPoint(e, r, i0, i0 * scale, r.tile > 0 ? particles[e.first + i0].spawnIndex / r.tile : i0 * scale);
        StripPoint b = stripPoint(e, r, i1, i1 * scale, r.tile > 0 ? particles[e.first + i1].spawnIndex / r.tile : i1 * scale);
        return segment(stripPoint(e, r, ib, 0, 0), a, b, stripPoint(e, r, ia, 0, 0), vertex, RENDER_RIBBON);
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
    return segment(p[0], p[1], p[2], p[3], vertex, RENDER_RIBBON);
}

[shader("vertex")]
ParticleVertex trailMain(uint vertex : SV_VertexID, uint index : SV_InstanceID)
{
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[push.emitter];
    ParticleRenderer r = rendererAt(push.renderer);
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
    uint4* trail = side + e.side + index * e.sideUnits + (((header >> 16) & PROGRAM_OVER) != 0 ? 2 : 0);
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
    return segment(q[0], q[1], q[2], q[3], vertex, RENDER_TRAIL);
}

/** A smooth value in about [-1, 1] along a beam at `t`, moving with time. */
float wave(float t, float time, float phase)
{
    return 0.6 * sin(17.0 * t + 5.0 * time + phase) + 0.4 * sin(41.0 * t - 7.0 * time + 2.0 * phase);
}

[shader("vertex")]
ParticleVertex beamMain(uint vertex : SV_VertexID)
{
    ParticleEmitter* emitters = push.emitters;
    ParticleEmitter e = emitters[push.emitter];
    ParticleRenderer r = rendererAt(push.renderer);
    View* view = push.view;
    float3 start = relativeTo(e, (e.flags & EMITTER_LOCAL) != 0 ? 0 : e.position, view.position, view.positionLow);
    float3 end = push.end;
    float3 direction = end - start;
    float3 side = cross(direction, -start);
    side = dot(side, side) > 1e-20 ? normalize(side) : float3(0, 1, 0);
    uint m = max(r.segments, 1u);
    uint s = vertex / 6;
    StripPoint q[4];
    for (int k = -1; k <= 2; k++)
    {
        float t = float(clamp(int(s) + k, 0, int(m))) / float(m);
        StripPoint sp;
        sp.position = start + direction * t + side * (r.noise * sin(3.14159265 * t) * wave(t, push.time, 0));
        sp.width = r.width * lerp(r.widthTail, r.widthHead, t);
        sp.color = r.color;
        sp.u = r.tile > 0 ? t * length(direction) / r.tile : t;
        q[k + 1] = sp;
    }
    return segment(q[0], q[1], q[2], q[3], vertex, RENDER_BEAM);
}

/** Frame `f` of the flipbook at `uv` within it. */
float4 frame(ParticleRenderer r, uint f, float2 uv)
{
    uint columns = max(r.columns, 1u);
    uint rows = max(r.rows, 1u);
    float2 cell = float2(f % columns, f / columns);
    return textures[NonUniformResourceIndex(push.texture)].Sample(samplers[LINEAR_CLAMP],
        (cell + uv) / float2(columns, rows));
}

[shader("fragment")]
float4 fragmentMain(ParticleVertex v) : SV_Target
{
    ParticleRenderer r = rendererAt(push.renderer);
    bool strip = v.frames.w != RENDER_BILLBOARD;
    float4 texel;
    if (push.texture == NO_TEXTURE)
    {
        // a round sprite, or a strip soft at its edges
        float2 d = strip ? float2(0, 2 * v.uv.y - 1) : 2 * v.uv - 1;
        texel = float4(1, 1, 1, saturate(1 - dot(d, d)));
    }
    else if (strip)
    {
        texel = textures[NonUniformResourceIndex(push.texture)].Sample(samplers[LINEAR_REPEAT], v.uv);
    }
    else
    {
        texel = frame(r, v.frames.x, v.uv);
        if (v.frames.z != 0)
        {
            texel = lerp(texel, frame(r, v.frames.y, v.uv), float(v.frames.z) / 65535.0);
        }
    }
    float4 color = v.color * texel;
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
