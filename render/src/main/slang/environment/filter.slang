// An environment's filtering (engine-render's environment requests), as the glTF sample viewer filters its own: a
// panorama made a radiance cube with a mip chain, prefiltered for GGX reflections (a level per roughness) and for sheen
// (the Charlie distribution), projected on spherical harmonics for diffuse light, and the BRDF lookup table those
// reflections are scaled by. Cube levels are written as 2D arrays of their faces, in Vulkan's face order (+X, -X, +Y,
// -Y, +Z, -Z), and read as cubes.
import bindless;

static const float PI = 3.14159265;

struct FilterPush
{
    uint source;        // sampled slot: the panorama (2D), or the radiance cube
    uint target;        // storage slot: a cube level (a 2D array of faces), or the lookup table (2D)
    uint size;          // the target level's size, texels a side
    uint sourceSize;    // the source's level 0, texels a side (a panorama: its width)
    float roughness;    // the level's roughness (perceptual)
    uint samples;       // importance samples per texel
    uint sourceMips;    // the source's mip levels
    uint pad;
    float* sh;          // spherical harmonics: 9 coefficients x rgb, written by shMain
    float4 color;       // uniformMain's color (linear)
};

[[vk::push_constant]]
ConstantBuffer<FilterPush> push;

/** The direction through the middle of texel `texel` of face `face` of a cube level `size` texels a side. */
float3 cubeDirection(uint face, float2 texel, float size)
{
    float2 uv = (texel + 0.5) / size * 2 - 1;
    switch (face)
    {
    case 0: return normalize(float3(1, -uv.y, -uv.x));
    case 1: return normalize(float3(-1, -uv.y, uv.x));
    case 2: return normalize(float3(uv.x, 1, uv.y));
    case 3: return normalize(float3(uv.x, -1, -uv.y));
    case 4: return normalize(float3(uv.x, -uv.y, 1));
    default: return normalize(float3(-uv.x, -uv.y, -1));
    }
}

/**
 * Where direction `d` looks in an equirectangular panorama: u around +Y (from +X towards +Z), v from +Y at the top to
 * -Y at the bottom.
 */
float2 panoramaUv(float3 d)
{
    return float2(0.5 + atan2(d.z, d.x) / (2 * PI), acos(clamp(d.y, -1.0, 1.0)) / PI);
}

/** Level 0 of the radiance cube from the panorama: one thread per texel of a face. */
[shader("compute")]
[numthreads(8, 8, 1)]
void panoramaMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float3 d = cubeDirection(id.z, float2(id.xy), float(push.size));
    // The panorama's level whose texels are about the size of the cube's: a 2:1 panorama of width W spans the equator
    // with W texels, the cube with 4 x size.
    float lod = max(0.0, log2(float(push.sourceSize) / (4.0 * push.size)));
    float3 c = textures[push.source].SampleLevel(samplers[LINEAR_REPEAT], panoramaUv(d), lod).rgb;
    imageArrays[push.target][id] = float4(c, 1);
}

/** Level 0 of the radiance cube of an environment of one color, push.color everywhere. */
[shader("compute")]
[numthreads(8, 8, 1)]
void uniformMain(uint3 id : SV_DispatchThreadID)
{
    if (all(id.xy < push.size))
    {
        imageArrays[push.target][id] = float4(push.color.rgb, 1);
    }
}

/** A level of the radiance cube from the one below. */
[shader("compute")]
[numthreads(8, 8, 1)]
void downsampleMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    // Each texel the mean of the four below it: the cube's mip chain, for filtered importance sampling.
    int3 base = int3(id.xy * 2, id.z);
    float4 sum = imageArrays[push.source][base] + imageArrays[push.source][base + int3(1, 0, 0)]
        + imageArrays[push.source][base + int3(0, 1, 0)] + imageArrays[push.source][base + int3(1, 1, 0)];
    imageArrays[push.target][id] = sum * 0.25;
}

/** The Hammersley point `i` of `n`: well spread samples on the unit square. */
float2 hammersley(uint i, uint n)
{
    return float2(float(i) / float(n), float(reversebits(i)) * 2.3283064365386963e-10);
}

/** A frame around `n`: tangent, bitangent. */
float3x3 frame(float3 n)
{
    float3 up = abs(n.y) < 0.999 ? float3(0, 1, 0) : float3(1, 0, 0);
    float3 t = normalize(cross(up, n));
    float3 b = cross(n, t);
    return float3x3(t, b, n);
}

float ggx(float nh, float a)
{
    float a2 = a * a;
    float d = nh * nh * (a2 - 1) + 1;
    return a2 / (PI * d * d);
}

float charlie(float nh, float a)
{
    float sin2 = max(1 - nh * nh, 1.0e-4);
    return (2 + 1 / a) * pow(sin2, 0.5 / a) / (2 * PI);
}

/**
 * The level of the radiance cube to read for a sample of density `pdf` among `samples` (filtered importance sampling,
 * Krivanek and Colbert 2008): the one whose texels cover about the solid angle the sample stands for.
 */
float sampleLod(float pdf)
{
    float sample = 1.0 / (float(push.samples) * max(pdf, 1.0e-6));
    float texel = 4 * PI / (6.0 * push.sourceSize * push.sourceSize);
    return clamp(0.5 * log2(sample / texel) + 1, 0.0, float(push.sourceMips - 1));
}

/** One level of the GGX-prefiltered cube: its roughness's reflections, the view along the normal (Karis 2013). */
[shader("compute")]
[numthreads(8, 8, 1)]
void ggxMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float3 n = cubeDirection(id.z, float2(id.xy), float(push.size));
    if (push.roughness <= 0)
    {
        imageArrays[push.target][id] = float4(cubes[push.source].SampleLevel(samplers[LINEAR_CLAMP], n, 0).rgb, 1);
        return;
    }
    float a = push.roughness * push.roughness;
    float3x3 tbn = frame(n);
    float3 sum = 0;
    float weight = 0;
    for (uint i = 0; i < push.samples; i++)
    {
        float2 xi = hammersley(i, push.samples);
        float phi = 2 * PI * xi.x;
        float cosTheta = sqrt((1 - xi.y) / (1 + (a * a - 1) * xi.y));
        float sinTheta = sqrt(1 - cosTheta * cosTheta);
        float3 h = mul(float3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta), tbn);
        float3 l = 2 * dot(n, h) * h - n;
        float nl = dot(n, l);
        if (nl > 0)
        {
            float pdf = ggx(cosTheta, a) / 4; // n = v: n.h = v.h
            sum += cubes[push.source].SampleLevel(samplers[LINEAR_CLAMP], l, sampleLod(pdf)).rgb * nl;
            weight += nl;
        }
    }
    imageArrays[push.target][id] = float4(sum / max(weight, 1.0e-6), 1);
}

/** One level of the sheen-prefiltered cube: the Charlie distribution's reflections (as the glTF sample viewer). */
[shader("compute")]
[numthreads(8, 8, 1)]
void charlieMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float3 n = cubeDirection(id.z, float2(id.xy), float(push.size));
    float a = max(push.roughness, 0.07);
    a = a * a;
    float3x3 tbn = frame(n);
    float3 sum = 0;
    float weight = 0;
    for (uint i = 0; i < push.samples; i++)
    {
        float2 xi = hammersley(i, push.samples);
        float phi = 2 * PI * xi.x;
        float sinTheta = pow(xi.y, a / (2 * a + 1));
        float cosTheta = sqrt(1 - sinTheta * sinTheta);
        float3 h = mul(float3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta), tbn);
        float3 l = 2 * dot(n, h) * h - n;
        float nl = dot(n, l);
        if (nl > 0)
        {
            float pdf = charlie(cosTheta, a) / 4;
            sum += cubes[push.source].SampleLevel(samplers[LINEAR_CLAMP], l, sampleLod(pdf)).rgb * nl;
            weight += nl;
        }
    }
    imageArrays[push.target][id] = float4(sum / max(weight, 1.0e-6), 1);
}

groupshared float shared[256 * 27];

/**
 * The radiance cube's level `push.size` projected on the first nine spherical harmonics, convolved with the cosine
 * lobe and divided by pi (Ramamoorthi and Hanrahan 2001): coefficients whose sum at a normal is the diffuse light
 * there (irradiance / pi). One group of 256 threads, each over a share of the texels, then a reduction.
 */
[shader("compute")]
[numthreads(256, 1, 1)]
void shMain(uint thread : SV_GroupIndex)
{
    float c[27];
    for (int k = 0; k < 27; k++)
    {
        c[k] = 0;
    }
    uint size = push.size;
    uint texels = size * size * 6;
    for (uint t = thread; t < texels; t += 256)
    {
        uint face = t / (size * size);
        uint2 xy = uint2(t % size, (t / size) % size);
        float2 uv = (float2(xy) + 0.5) / size * 2 - 1;
        float solid = 4.0 / (size * size) / pow(1 + dot(uv, uv), 1.5); // the texel's solid angle
        float3 d = cubeDirection(face, float2(xy), float(size));
        float3 radiance = imageArrays[push.source][uint3(xy, face)].rgb * solid;
        float y[9] = { 0.282095, 0.488603 * d.y, 0.488603 * d.z, 0.488603 * d.x, 1.092548 * d.x * d.y,
            1.092548 * d.y * d.z, 0.315392 * (3 * d.z * d.z - 1), 1.092548 * d.x * d.z,
            0.546274 * (d.x * d.x - d.y * d.y) };
        for (int b = 0; b < 9; b++)
        {
            c[3 * b] += radiance.r * y[b];
            c[3 * b + 1] += radiance.g * y[b];
            c[3 * b + 2] += radiance.b * y[b];
        }
    }
    for (int k = 0; k < 27; k++)
    {
        shared[thread * 27 + k] = c[k];
    }
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = 128; stride > 0; stride >>= 1)
    {
        if (thread < stride)
        {
            for (int k = 0; k < 27; k++)
            {
                shared[thread * 27 + k] += shared[(thread + stride) * 27 + k];
            }
        }
        GroupMemoryBarrierWithGroupSync();
    }
    if (thread < 27)
    {
        // The cosine lobe's bands, over pi: 1, 2/3, 1/4.
        uint band = thread < 3 ? 0 : thread < 12 ? 1 : 2;
        float lobe = band == 0 ? 1.0 : band == 1 ? 2.0 / 3.0 : 0.25;
        push.sh[thread] = shared[thread] * lobe;
    }
}

/**
 * The BRDF lookup table, by n.v (across) and roughness (down): red and green GGX's split-sum scale and bias (Karis
 * 2013), blue the sheen's directional albedo (the Charlie distribution with Neubelt's visibility), for reflections of
 * the environment and the base a sheen leaves.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void brdfMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float nv = max((id.x + 0.5) / push.size, 1.0e-3);
    float roughness = (id.y + 0.5) / push.size;
    float3 v = float3(sqrt(1 - nv * nv), 0, nv);
    float a = roughness * roughness;
    float scale = 0;
    float bias = 0;
    float sheen = 0;
    float sheenA = max(roughness, 0.07);
    sheenA *= sheenA;
    for (uint i = 0; i < push.samples; i++)
    {
        float2 xi = hammersley(i, push.samples);
        float phi = 2 * PI * xi.x;
        float cosTheta = sqrt((1 - xi.y) / (1 + (a * a - 1) * xi.y));
        float sinTheta = sqrt(1 - cosTheta * cosTheta);
        float3 h = float3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
        float3 l = 2 * dot(v, h) * h - v;
        float nl = saturate(l.z);
        float nh = saturate(h.z);
        float vh = saturate(dot(v, h));
        if (nl > 0)
        {
            // Smith-GGX correlated visibility, times 4 nl vh / nh: the split sum's integrand over the GGX samples
            float gv = nl * sqrt(nv * nv * (1 - a * a) + a * a);
            float gl = nv * sqrt(nl * nl * (1 - a * a) + a * a);
            float g = 0.5 / max(gv + gl, 1.0e-6) * 4 * nl * vh / max(nh, 1.0e-6);
            float fc = pow(1 - vh, 5);
            scale += (1 - fc) * g;
            bias += fc * g;
        }
        // The sheen's albedo: its lobe over the hemisphere, sampled uniformly
        float z = xi.y;
        float r = sqrt(1 - z * z);
        float3 u = float3(r * cos(phi), r * sin(phi), z);
        float3 hu = normalize(u + v);
        float visibility = 1 / (4 * (u.z + nv - u.z * nv));
        sheen += charlie(saturate(hu.z), sheenA) * visibility * u.z * 2 * PI;
    }
    images[push.target][id.xy] = float4(scale / push.samples, bias / push.samples, sheen / push.samples, 1);
}
