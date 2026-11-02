// Drawing particles in a view (effects.md §2.5): cullMain, a thread per draw, tests each emitter instance's GPU box
// against the view's frustum and writes its indirect draw (six vertices per particle, as many instances as it has
// particles, or none); vertexMain turns each particle into a billboard facing the camera's position, the view plane,
// along its velocity or about its emitter's up axis, with its flipbook frames; fragmentMain samples its texture (or a
// round sprite), tests the opaque depth itself, fades softly into it by depth or by the view ray's path through the
// particle's sphere, and writes premultiplied color: alpha blended, additive, or between them (alpha to add).
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
    uint drawCount;                // (cullMain)
    uint draw;                     // the draw recorded: its place in the list (vertexMain)
    uint emitter;                  // its emitter's place in the frame's list
    uint renderer;                 // its renderer's word
    uint texture;                  // its texture's sampled slot, or NO_TEXTURE
    uint depth;                    // the scene's depth, sampled slot
};

[[vk::push_constant]]
ConstantBuffer<ParticleDrawPush> push;

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
    uint at = e.instance * STATE_WORDS;
    uint alive = state[at + STATE_ALIVE];
    bool visible = alive > 0 && state[at + STATE_BOUNDS] <= state[at + STATE_BOUNDS + 3];
    if (visible)
    {
        float3 low;
        float3 high;
        stateBounds(state, e.instance, low, high);
        float3 center = relativeTo(e, 0.5 * (low + high), view.position, view.positionLow);
        float radius = 0.5 * length(high - low);
        for (uint i = 0; i < 6 && visible; i++)
        {
            float4 plane = view.planes[i];
            visible = dot(plane.xyz, center) + plane.w >= -radius;
        }
    }
    uint* commands = push.commands;
    commands[4 * id.x] = 6;
    commands[4 * id.x + 1] = visible ? alive : 0;
    commands[4 * id.x + 2] = 0;
    commands[4 * id.x + 3] = 0;
}

struct ParticleVertex
{
    float4 position : SV_Position;
    float4 color : COLOR;          // straight alpha
    float2 uv : TEXCOORD0;         // across the quad, 0 to 1
    float3 sphere : TEXCOORD1;     // the particle's view distance, its radius, and the fragment's own view distance
    nointerpolation uint3 frames : TEXCOORD2; // flipbook frames, and the blend between them as 16-bit fixed point
};

/** Row `r` of a view-projection matrix, without its last column: the camera's axes, scaled. */
float3 row(float4x4 m, uint r)
{
    return float3(mul(m, float4(1, 0, 0, 0))[r], mul(m, float4(0, 1, 0, 0))[r], mul(m, float4(0, 0, 1, 0))[r]);
}

/** The renderer record's word `i`. */
uint word(uint i)
{
    uint* words = push.words;
    return words[push.renderer + i];
}

/** The renderer's float `i`. */
float real(uint i)
{
    return asfloat(word(i));
}

[shader("vertex")]
ParticleVertex vertexMain(uint vertex : SV_VertexID, uint index : SV_InstanceID)
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
        o.position = float4(0, 0, 2, 1); // outside: clipped
        o.color = 0;
        o.uv = 0;
        o.sphere = 0;
        o.frames = 0;
        return o;
    }
    float3 center = relativeTo(e, p.position, view.position, view.positionLow);
    float3 velocity = (e.flags & EMITTER_LOCAL) != 0 ? turn(e.rotation, p.velocity) : p.velocity;
    float3 toCamera = normalize(-center);
    float3 right;
    float3 up;
    float2 half = 0.5 * p.size;
    uint facing = word(1);
    if (facing == FACING_VIEW_PLANE)
    {
        right = normalize(row(viewProjection, 0));
        up = -normalize(row(viewProjection, 1));
    }
    else if (facing == FACING_VELOCITY || facing == FACING_AXIS)
    {
        float speed = length(velocity);
        float3 axis = facing == FACING_AXIS ? turn(e.rotation, float3(0, 1, 0))
            : speed > 1e-6 ? velocity / speed : float3(0, 1, 0);
        float3 side = cross(axis, toCamera);
        right = dot(side, side) > 1e-12 ? normalize(side) : normalize(cross(axis, float3(0.577, 0.577, 0.577)));
        up = axis;
        if (facing == FACING_VELOCITY)
        {
            half.y *= 1 + real(4) * speed;
        }
    }
    else // FACING_POSITION (and FREE, until particles have normals)
    {
        float3 side = cross(float3(0, 1, 0), toCamera);
        right = dot(side, side) > 1e-12 ? normalize(side) : float3(1, 0, 0);
        up = cross(toCamera, right);
    }
    if (facing == FACING_POSITION || facing == FACING_VIEW_PLANE)
    {
        float c = cos(p.rotation);
        float s = sin(p.rotation);
        float3 r = c * right + s * up;
        up = c * up - s * right;
        right = r;
    }
    // Two triangles: corners (-1, -1), (1, -1), (-1, 1), (1, -1), (1, 1), (-1, 1).
    uint corner = vertex % 6;
    float2 xy = float2(corner == 1 || corner == 3 || corner == 4 ? 1 : -1, corner == 2 || corner >= 4 ? 1 : -1);
    float3 world = center + right * (xy.x * half.x) + up * (xy.y * half.y);
    o.position = mul(viewProjection, float4(world, 1));
    o.color = p.color;
    o.uv = float2(0.5 + 0.5 * xy.x, 0.5 - 0.5 * xy.y);
    float centerW = mul(viewProjection, float4(center, 1)).w;
    o.sphere = float3(centerW, max(half.x, half.y), o.position.w);
    // Flipbook frames: by age, looping, or over the particle's life.
    float frames = real(8);
    float rate = real(9);
    float t = rate > 0 ? p.age * rate : p.age / p.lifetime * frames;
    t = (word(2) & DRAW_LOOP) != 0 ? t - frames * floor(t / frames) : clamp(t, 0.0, frames - 1);
    uint f0 = min(uint(t), uint(frames) - 1);
    uint f1 = (word(2) & DRAW_LOOP) != 0 ? (f0 + 1) % uint(frames) : min(f0 + 1, uint(frames) - 1);
    float blend = (word(2) & DRAW_BLEND_FRAMES) != 0 ? t - floor(t) : 0;
    o.frames = uint3(f0, f1, uint(blend * 65535));
    return o;
}

/** Frame `f` of the flipbook at `uv` within it. */
float4 frame(uint f, float2 uv)
{
    uint columns = max(word(6), 1u);
    uint rows = max(word(7), 1u);
    float2 cell = float2(f % columns, f / columns);
    return textures[NonUniformResourceIndex(push.texture)].Sample(samplers[LINEAR_CLAMP],
        (cell + uv) / float2(columns, rows));
}

[shader("fragment")]
float4 fragmentMain(ParticleVertex v) : SV_Target
{
    float4 texel;
    if (push.texture == NO_TEXTURE)
    {
        float2 d = 2 * v.uv - 1;
        texel = float4(1, 1, 1, saturate(1 - dot(d, d)));
    }
    else
    {
        texel = frame(v.frames.x, v.uv);
        if (v.frames.z != 0)
        {
            texel = lerp(texel, frame(v.frames.y, v.uv), float(v.frames.z) / 65535.0);
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
    float soft = real(5);
    if (soft > 0)
    {
        if ((word(2) & DRAW_VOLUME) != 0)
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
            color.a *= saturate((scene - distance) / soft);
        }
    }
    float alphaToAdd = real(3);
    return float4(color.rgb * color.a, color.a * (1 - alphaToAdd));
}
