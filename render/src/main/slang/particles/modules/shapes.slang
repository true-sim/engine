// The built-in shapes (E24): initializers putting a newborn particle in its emitter's space and giving it a direction
// (along which init.speed moves it); the mesh, bone and mask shapes read the frame's emission sources and textures.
// The same draws as scene-effects' CpuSimulation (Shape), where it has them.
module shapes;

import particles.particle;
import bindless;
import meshlets.common;

/** A unit direction uniform on the sphere, from two draws from `salt` on. */
public float3 randomDirection(uint seed, uint salt)
{
    float y = 1 - 2 * random(seed, salt);
    float r = sqrt(max(0.0, 1 - y * y));
    float turn = TAU * random(seed, salt + 1);
    return float3(r * cos(turn), y, r * sin(turn));
}

/** A position on a disc of `radius` in the XZ plane, uniform by area. */
public float3 disc(float radius, uint seed, uint salt)
{
    float rho = radius * sqrt(random(seed, salt + 8));
    float turn = TAU * random(seed, salt + 9);
    return float3(rho * cos(turn), 0, rho * sin(turn));
}

/** Byte `i` of the pool's meshlet triangles. */
public uint triangleByte(uint* triangles, uint i)
{
    return (triangles[i >> 2] >> ((i & 3) * 8)) & 0xff;
}

/** Pool vertex `v`'s normal, or `fallback` where the mesh has none. */
public float3 vertexNormal(MeshEntry mesh, uint* attributes, uint v, float3 fallback)
{
    return (mesh.attributeFormat & 1) != 0 ? decodeOctahedral(attributeWord(mesh, attributes, v, 0)) : fallback;
}

/** Pool vertex `v`'s first texture coordinates (the mesh has them). */
public float2 vertexUv(MeshEntry mesh, uint* attributes, uint v)
{
    uint word = attributeWord(mesh, attributes, v, 2);
    return float2(decodeHalf(word & 0xffff), decodeHalf(word >> 16));
}

/**
 * A point of the frame's source `at`'s mesh, as `mode` says (MESH_*), drawn from `salt` on, in the emitter's frame:
 * on a triangle its alias table by area picks (uniform within it), on a vertex, or in the tetrahedron from the box's
 * center to a triangle its table by volume picks; with the surface's normal (outwards from the center in the volume)
 * and its texture coordinates where the mesh has them. False without a mesh.
 */
public bool meshPoint(ParticleFrame* frame, uint at, uint mode, uint seed, uint salt, out float3 position,
    out float3 normal, out float2 uv, out bool textured)
{
    ParticleSource* sources = frame.sources;
    position = 0;
    normal = float3(0, 1, 0);
    uv = 0;
    textured = false;
    uint meshIndex = sources[at].mesh;
    if (meshIndex == NO_MESH)
    {
        return false;
    }
    MeshEntry* meshes = (MeshEntry*) frame.meshes;
    MeshEntry mesh = meshes[meshIndex];
    uint2* positions = frame.positions;
    uint* attributes = frame.attributes;
    if (mode == MESH_VERTICES)
    {
        uint vertices = sources[at].vertices;
        uint v = mesh.vertexBase + min(uint(random(seed, salt + 8) * float(vertices)), vertices - 1);
        position = decodePosition(positions[v], mesh);
        normal = vertexNormal(mesh, attributes, v, normal);
        textured = (mesh.attributeFormat & 4) != 0;
        uv = textured ? vertexUv(mesh, attributes, v) : 0;
    }
    else
    {
        uint n = sources[at].triangles;
        uint* table = (uint*) (sources[at].table + (mode == MESH_VOLUME ? 12 * uint64_t(n) : 0));
        uint i = min(uint(random(seed, salt + 8) * float(n)), n - 1);
        uint chosen = random(seed, salt + 9) < asfloat(table[3 * i + 1]) ? i : table[3 * i + 2];
        uint packed = table[3 * chosen];
        uint* meshlets = frame.meshlets;
        uint* meshletVertices = frame.meshletVertices;
        uint* triangles = frame.meshletTriangles;
        uint w = (mesh.meshletBase + (packed >> 8)) * 8;
        uint first = mesh.meshletVertexBase + meshlets[w];
        uint corner = mesh.indexBase + meshlets[w + 1] + 3 * (packed & 0xff);
        uint3 v = uint3(meshletVertices[first + triangleByte(triangles, corner)],
            meshletVertices[first + triangleByte(triangles, corner + 1)],
            meshletVertices[first + triangleByte(triangles, corner + 2)]) + mesh.vertexBase;
        float3 a = decodePosition(positions[v.x], mesh);
        float3 b = decodePosition(positions[v.y], mesh);
        float3 c = decodePosition(positions[v.z], mesh);
        if (mode == MESH_VOLUME)
        {
            // Uniform in the tetrahedron (center, a, b, c): a point of the unit cube folded into it (Rocchini and
            // Cignoni 2000)
            float3 center = mesh.boxMin + 0.5 * mesh.boxExtent;
            float s = random(seed, salt + 10);
            float t = random(seed, salt + 11);
            float u = random(seed, salt + 12);
            if (s + t > 1)
            {
                s = 1 - s;
                t = 1 - t;
            }
            if (t + u > 1)
            {
                float k = u;
                u = 1 - s - t;
                t = 1 - k;
            }
            else if (s + t + u > 1)
            {
                float k = u;
                u = s + t + u - 1;
                s = 1 - t - k;
            }
            position = (1 - s - t - u) * center + s * a + t * b + u * c;
            float3 away = position - center;
            normal = dot(away, away) > 1e-12 ? normalize(away) : normal;
        }
        else
        {
            float r = sqrt(random(seed, salt + 10));
            float q = random(seed, salt + 11);
            float3 bary = float3(1 - r, r * (1 - q), r * q);
            position = bary.x * a + bary.y * b + bary.z * c;
            float3 face = cross(b - a, c - a);
            face = dot(face, face) > 1e-20 ? normalize(face) : normal;
            if ((mesh.attributeFormat & 1) != 0)
            {
                normal = bary.x * vertexNormal(mesh, attributes, v.x, face)
                    + bary.y * vertexNormal(mesh, attributes, v.y, face)
                    + bary.z * vertexNormal(mesh, attributes, v.z, face);
            }
            else
            {
                normal = face;
            }
            textured = (mesh.attributeFormat & 4) != 0;
            if (textured)
            {
                uv = bary.x * vertexUv(mesh, attributes, v.x) + bary.y * vertexUv(mesh, attributes, v.y)
                    + bary.z * vertexUv(mesh, attributes, v.z);
            }
        }
    }
    // Into the emitter's frame; normals by the cofactors, so a scaled mesh's stay perpendicular
    float4 r0 = sources[at].toEmitter[0];
    float4 r1 = sources[at].toEmitter[1];
    float4 r2 = sources[at].toEmitter[2];
    position = float3(dot(r0.xyz, position) + r0.w, dot(r1.xyz, position) + r1.w, dot(r2.xyz, position) + r2.w);
    float3x3 cofactor = float3x3(cross(r1.xyz, r2.xyz), cross(r2.xyz, r0.xyz), cross(r0.xyz, r1.xyz));
    float3 turned = mul(transpose(cofactor), normal);
    normal = dot(turned, turned) > 1e-20 ? normalize(turned) : float3(0, 1, 0);
    return true;
}

/**
 * A point within `radius` of the chain of the frame's source `at`'s joints, uniform by length along it (and by area
 * across it), drawn from `salt` on, with the direction away from the chain. False without joints.
 */
public bool bonePoint(ParticleFrame* frame, uint at, float radius, uint seed, uint salt, out float3 position,
    out float3 direction)
{
    ParticleSource* sources = frame.sources;
    uint n = min(sources[at].joints, MAX_JOINTS);
    position = 0;
    direction = randomDirection(seed, salt + 10);
    if (n == 0)
    {
        return false;
    }
    float total = 0;
    for (uint i = 0; i + 1 < n; i++)
    {
        total += length(sources[at].chain[i + 1].xyz - sources[at].chain[i].xyz);
    }
    float along = random(seed, salt + 8) * total;
    float3 a = sources[at].chain[0].xyz;
    float3 b = a;
    float t = 0;
    for (uint i = 0; i + 1 < n; i++)
    {
        a = sources[at].chain[i].xyz;
        b = sources[at].chain[i + 1].xyz;
        float l = length(b - a);
        t = l > 0 ? saturate(along / l) : 0.0;
        if (along <= l)
        {
            break;
        }
        along -= l;
    }
    position = a + (b - a) * t;
    float3 axis = b - a;
    if (dot(axis, axis) > 1e-12)
    {
        axis = normalize(axis);
        float3 across = direction - axis * dot(direction, axis);
        direction = dot(across, across) > 1e-12 ? normalize(across) : direction;
    }
    position += direction * (radius * sqrt(random(seed, salt + 9)));
    return true;
}

/** At the emitter, in a random direction. */
[ParticleModule("shape.point")]
[BuiltInTwin]
public struct PointShape
{
    public void init(inout ParticleInit s, inout Particle p)
    {
        p.position = 0;
        s.direction = randomDirection(p.seed, s.salt + 8);
    }
};

/** In a sphere's shell (of thickness 1: the whole ball), uniform by volume, facing out. */
[ParticleModule("shape.sphere")]
[BuiltInTwin]
public struct SphereShape
{
    public float radius = 1;
    public float thickness = 1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        s.direction = randomDirection(p.seed, s.salt + 8);
        float inner = 1 - min(1.0, max(0.0, thickness));
        float u = random(p.seed, s.salt + 10);
        float cube = inner * inner * inner + (1 - inner * inner * inner) * u;
        float r = radius * (cube > 0 ? pow(cube, 1.0 / 3.0) : 0.0);
        p.position = s.direction * r;
    }
};

/** In a box of x by y by z about the emitter, in a random direction. */
[ParticleModule("shape.box")]
[BuiltInTwin]
public struct BoxShape
{
    public float x = 1;
    public float y = 1;
    public float z = 1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.position = float3((random(p.seed, s.salt + 8) - 0.5) * x, (random(p.seed, s.salt + 9) - 0.5) * y,
            (random(p.seed, s.salt + 10) - 0.5) * z);
        s.direction = randomDirection(p.seed, s.salt + 11);
    }
};

/** On a disc of the radius, in a direction within the angle of +Y. */
[ParticleModule("shape.cone")]
[BuiltInTwin]
public struct ConeShape
{
    public float angle = 0.5;
    public float radius = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.position = disc(radius, p.seed, s.salt);
        float c = 1 - random(p.seed, s.salt + 10) * (1 - cos(angle));
        float sine = sqrt(max(0.0, 1 - c * c));
        float around = TAU * random(p.seed, s.salt + 11);
        s.direction = float3(sine * cos(around), c, sine * sin(around));
    }
};

/** On a disc of the radius in the XZ plane, facing +Y. */
[ParticleModule("shape.disc")]
[BuiltInTwin]
public struct DiscShape
{
    public float radius = 1;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.position = disc(radius, p.seed, s.salt);
        s.direction = float3(0, 1, 0);
    }
};

/**
 * On the emitter's mesh (its emission source), by `mode` (MESH_*), facing along its surface; kept with the chance
 * 1 - upward + upward max(0, n.y), so upward 1 keeps faces looking up. Nothing to emit from (no mesh named, or not
 * loaded yet): born dead, not in a heap at the emitter.
 */
[ParticleModule("shape.mesh")]
public struct MeshShape
{
    public float mode = 0;
    public float upward = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.position = 0;
        s.direction = float3(0, 1, 0);
        s.textured = false;
        s.masked = s.masked || s.emitter.source == NO_SOURCE || !meshPoint(s.frame, s.emitter.source,
            uint(mode + 0.5), p.seed, s.salt, p.position, s.direction, s.uv, s.textured);
        s.masked = s.masked || !(random(p.seed, s.salt + 13) < 1 - upward + upward * max(0.0, s.direction.y));
    }
};

/** Within the radius of the emitter's chain of joints (its emission source), facing away from it. */
[ParticleModule("shape.bones")]
public struct BonesShape
{
    public float radius = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        p.position = 0;
        s.direction = randomDirection(p.seed, s.salt + 10);
        s.textured = false;
        s.masked = s.masked || s.emitter.source == NO_SOURCE || !bonePoint(s.frame, s.emitter.source, radius,
            p.seed, s.salt, p.position, s.direction);
    }
};

/**
 * Keeps the particles the shapes put where the mask is dense: channel `channel` of the texture (4: luminance) at the
 * mesh's texture coordinates, or over sizeX by sizeZ about the emitter in the XZ plane; above the threshold, or with
 * the density's chance where the threshold is 0.
 */
[ParticleModule("shape.mask")]
public struct MaskShape
{
    public float channel = 4;
    public ModuleTexture mask;
    public float sizeX = 1;
    public float sizeZ = 1;
    public float threshold = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        if (mask == NO_TEXTURE)
        {
            return;
        }
        float2 at = s.textured ? s.uv : float2(p.position.x / sizeX, p.position.z / sizeZ) + 0.5;
        float4 texel = textures[NonUniformResourceIndex(mask)].SampleLevel(samplers[LINEAR_CLAMP], at, 0);
        uint c = uint(channel + 0.5);
        float density = c < 4 ? texel[c] : dot(texel.rgb, float3(0.2126, 0.7152, 0.0722));
        s.masked = s.masked || (threshold > 0 ? density < threshold : !(random(p.seed, s.salt + 12) < density));
    }
};
