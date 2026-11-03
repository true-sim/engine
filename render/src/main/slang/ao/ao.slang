// Ambient occlusion (engine-render's AmbientOcclusion), in passes over a view's render region:
//
// - screenMain (GTAO, BITMASK): per pixel, slices through the hemisphere around the view direction (Jimenez, Wu, Pesce
//   and Jarabo 2016), each searched along its screen direction on both sides through the depth. GTAO keeps each
//   side's highest horizon and integrates the cosine-weighted visible arc between them in closed form, samples fading
//   out over the last part of the radius; BITMASK (Therrien, Levesque and Gilet 2023) gives surfaces a thickness and
//   marks which of 32 sectors of the slice each sample's slab covers, so light passes behind thin objects, the sectors
//   spread so each holds the same cosine-weighted measure (the slice's measure, cos(t - n) |sin t|, from the closed
//   form of GTAO's arc integral), and gathers one bounce of the scene's color from the sectors newly covered. Both
//   give a bent normal (the visible arcs' mean direction, GTAO's closed form) for specular occlusion. Slices are
//   summed as a ratio of visible to whole measure, so an open surface is exactly 1 at any tilt.
// - raysMain (RAYS, issue #34): cosine-distributed rays of the radius's length by ray queries into the frame's top
//   level; occlusion is exact, off-screen occluders included; noisy, denoised after (denoise/denoise.slang).
// - compositeMain: the result replaces what shading added, through the surface outputs: the diffuse light from all
//   around the normal (the view's indirect-light table's diffuse term, the probe volume where one lights it) is
//   weighed by the diffuse weight in the pixel's color, so color + diffuse weight x ((ao - 1) x diffuse light +
//   bounce) darkens that term alone (design D12); the environment's reflection weighed by the reflectance is dimmed by
//   the specular occlusion (the cone of the visible directions around the bent normal against the GGX lobe's cone),
//   which is also written for screen-space reflections to dim the environment they replace by.
import meshlets.common;
import raytracing;

struct AoPush
{
    View* view;
    IndirectLight* indirect;  // with INDIRECT: the view's indirect-light table
    uint64_t rays;            // RayScene* (RAYS): the frame's top level
    float3 offset;            // RAYS: the camera's position less the top level's origin
    uint depth;               // sampled slot: the scene's depth (reverse: 1 near, 0 at infinity)
    uint normal;              // storage slots of the surface outputs
    uint diffuse;
    uint reflectance;
    uint material;
    uint color;               // screenMain: sampled slot of the scene's color (the bounce); compositeMain: storage slot
    uint ao;                  // storage slot: the bent normal in view space (rgb), the occlusion (a)
    uint bounce;              // storage slot: BITMASK's bounce (rgb); RAYS: the occluders' mean distance (r)
    uint occlusion;           // compositeMain: storage slot of the specular occlusion written for reflections
    uint size;                // the render region: width, height in the high 16 bits
    uint frame;               // the noise's frame (0 for a pattern fixed in time)
    uint slices;              // GTAO, BITMASK: slices per pixel
    uint steps;               // GTAO, BITMASK: samples per side of a slice; RAYS: rays per pixel
    float radius;             // in meters
    float thickness;          // BITMASK: surfaces' thickness along the view, in meters
    float falloff;            // GTAO: the part of the radius over which samples fade out
    float rayOffset;          // RAYS: how far rays start off the surface, per unit of distance from the camera
    uint flags;               // AO_*
};

[[vk::push_constant]]
ConstantBuffer<AoPush> push;

/** 0: GTAO; 1: BITMASK. */
[vk::constant_id(0)]
const uint MODE = 0;

/** 1: the view has an indirect-light table; 0: the constant sky (no pointer is read: pitfalls.md). */
[vk::constant_id(1)]
const uint INDIRECT = 0;

static const uint AO_BOUNCE = 1;         // BITMASK: gather the scene's color from covered sectors
static const uint AO_MULTI_BOUNCE = 2;   // GTAO's fit of interreflections by albedo (Jimenez et al. 2016)
static const uint AO_SPECULAR = 4;       // specular occlusion
static const uint AO_SHOW_AO = 8;        // debug: the image is the occlusion
static const uint AO_SHOW_SPECULAR = 16; // debug: the image is the specular occlusion
static const uint AO_SHOW_BOUNCE = 32;   // debug: the image is the bounce
static const uint AO_SKY = 64;           // a pixel showing nothing (written by the passes for the denoiser)

static const float PI = 3.14159265;
static const float HALF_PI = 1.57079633;

uint2 unpackSize(uint s)
{
    return uint2(s & 0xffff, s >> 16);
}

float sceneDepth(int2 p)
{
    return textures[push.depth].Load(int3(p, 0)).r;
}

/** Screen position `s` (pixels of the region) with projected depth `z` back into camera-relative space. */
float3 unproject(float2 s, float z, float2 size)
{
    float2 ndc = s / size * 2 - 1;
    float4 h = mul(push.view[0].inverseViewProjection, float4(ndc, z, 1));
    return h.xyz / h.w;
}

/** Interleaved gradient noise (Jimenez 2014) at pixel `p`, shifted by frame `frame`: in [0, 1). */
float gradientNoise(float2 p, uint frame)
{
    p += 5.588238 * float(frame & 63);
    return frac(52.9829189 * frac(dot(p, float2(0.06711056, 0.00583715))));
}

/** acos on [-1, 1], clamped. */
float safeAcos(float c)
{
    return acos(clamp(c, -1.0, 1.0));
}

/** The cosine-weighted measure of a slice from the view direction to angle `h` (signed), normal at angle `n`. */
float arc(float h, float n, float cosN)
{
    return (cosN + 2 * h * sin(n) - cos(2 * h - n)) / 4;
}

/** The measure from the slice's lowest angle n - pi/2 to `h`, signed angles from the view direction. */
float measureTo(float h, float n, float cosN)
{
    float low = n - HALF_PI;
    float toLow = low >= 0 ? arc(low, n, cosN) : -arc(low, n, cosN);
    float toH = h >= 0 ? arc(h, n, cosN) : -arc(h, n, cosN);
    return toH - toLow;
}

/** Sectors h0 to h1 (h0 <= h1, signed angles) as bits of 32 of equal measure; `total` the slice's whole measure. */
uint sectors(float h0, float h1, float n, float cosN, float total)
{
    float a = saturate(measureTo(clamp(h0, n - HALF_PI, n + HALF_PI), n, cosN) / total);
    float b = saturate(measureTo(clamp(h1, n - HALF_PI, n + HALF_PI), n, cosN) / total);
    // Sectors rounded to the nearest bounds: an interval covers as much as it measures, give or take half a sector
    // (a sample in the surface's own plane covers nothing)
    uint first = uint(round(a * 32));
    uint last = uint(round(b * 32));
    if (last <= first)
    {
        return 0;
    }
    uint count = last - first;
    uint bits = count >= 32 ? 0xffffffffu : (1u << count) - 1;
    return bits << first;
}

/** XeGTAO's rotation taking unit `a` onto unit `b`. */
float3x3 rotationFromTo(float3 a, float3 b)
{
    float3 v = cross(a, b);
    float c = dot(a, b);
    float f = 1 / (1 + c);
    float hvx = v.x * f;
    float hvz = v.z * f;
    float hvxy = hvx * v.y;
    float hvxz = hvx * v.z;
    float hvyz = hvz * v.y;
    return float3x3(c + hvx * v.x, hvxy - v.z, hvxz + v.y,
        hvxy + v.z, c + v.y * v.y / (1 + c), hvyz - v.x,
        hvxz - v.y, hvyz + v.x, c + hvz * v.z);
}

/** The view-space position of the region's pixel `p`, or a z of 0 where it shows nothing. */
float3 viewPosition(int2 p, float2 size, float3x3 axes)
{
    float z = sceneDepth(p);
    if (z <= 0)
    {
        return 0;
    }
    return toView(axes, unproject(float2(p) + 0.5, z, size));
}

/** Pixels per unit of distance at a view distance of 1, along the image's height. */
float pixelsPerUnit(float2 size, float3x3 axes)
{
    float3 row = push.view[0].viewProjection[1].xyz;
    return 0.5 * size.y * length(row - dot(row, axes[2]) * axes[2]);
}

void writeNone(uint2 p)
{
    images[push.ao][p] = float4(0, 0, -1, 1);
    images[push.bounce][p] = float4(0, 0, 0, 0);
}

/** GTAO or the visibility bitmask (MODE) at each pixel: occlusion, bent normal and (BITMASK) the bounce. */
[shader("compute")]
[numthreads(8, 8, 1)]
void screenMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    float3 p = viewPosition(int2(id.xy), size, axes);
    if (p.z <= 0)
    {
        writeNone(id.xy);
        return;
    }
    float3 normal = unpackNormal(uintImages[push.normal][id.xy].x);
    float3 v = normalize(-p);
    float radiusPixels = push.radius * pixelsPerUnit(size, axes) / p.z;
    if (radiusPixels < 1)
    {
        images[push.ao][id.xy] = float4(normal, 1);
        images[push.bounce][id.xy] = 0;
        return;
    }
    float falloffRange = max(push.falloff * push.radius, 1.0e-4);
    float falloffMul = -1 / falloffRange;
    float falloffAdd = push.radius / falloffRange; // 1 at radius - range, 0 at the radius
    float noiseSlice = gradientNoise(float2(id.xy), push.frame);
    float noiseStep = gradientNoise(float2(id.xy) + float2(37, 17), push.frame);
    uint slices = max(push.slices, 1u);
    uint steps = max(push.steps, 1u);
    float visible = 0;
    float whole = 0;
    float3 bent = 0;
    float3 light = 0;
    bool gather = MODE == 1 && (push.flags & AO_BOUNCE) != 0;
    for (uint s = 0; s < slices; s++)
    {
        float phi = (float(s) + noiseSlice) * PI / float(slices);
        float2 omega = float2(cos(phi), sin(phi));
        float3 direction = float3(omega, 0);
        float3 ortho = direction - dot(direction, v) * v;
        float3 axis = normalize(cross(ortho, v));
        float3 projected = normal - axis * dot(normal, axis);
        float projectedLength = length(projected);
        if (projectedLength < 1.0e-4)
        {
            continue;
        }
        float signN = dot(ortho, projected) >= 0 ? 1.0 : -1.0;
        float cosN = saturate(dot(projected, v) / projectedLength);
        float n = signN * acos(cosN);
        float total = cosN + n * sin(n); // the slice's whole measure, n - pi/2 to n + pi/2
        float lowCos0 = cos(n + HALF_PI);
        float lowCos1 = cos(n - HALF_PI);
        float horizonCos0 = lowCos0;
        float horizonCos1 = lowCos1;
        uint covered = 0;
        for (uint side = 0; side < 2; side++)
        {
            float sideSign = side == 0 ? 1.0 : -1.0;
            for (uint j = 0; j < steps; j++)
            {
                float t = (float(j) + noiseStep) / float(steps);
                float offset = max(t * t * radiusPixels, float(j) + 1);
                float2 at = float2(id.xy) + 0.5 + sideSign * omega * offset;
                if (any(at < 0) || any(at >= size))
                {
                    break;
                }
                int2 q = int2(at);
                float3 sp = viewPosition(q, size, axes);
                if (sp.z <= 0)
                {
                    continue;
                }
                float3 delta = sp - p;
                float distance = length(delta);
                if (distance < 1.0e-5)
                {
                    continue;
                }
                float3 toSample = delta / distance;
                if (dot(normal, toSample) <= 0.02)
                {
                    continue; // on or below the surface's own plane: nothing above it is hidden (depth's rounding)
                }
                // GTAO's horizon, fading out towards the radius (also BITMASK's bent normal)
                float weight = saturate(distance * falloffMul + falloffAdd);
                if (side == 0)
                {
                    horizonCos0 = max(horizonCos0, lerp(lowCos0, dot(toSample, v), weight));
                }
                else
                {
                    horizonCos1 = max(horizonCos1, lerp(lowCos1, dot(toSample, v), weight));
                }
                if (MODE == 1 && distance <= push.radius)
                {
                    // The slab from the sample's surface to its thickness behind it, along the view
                    float3 back = normalize(delta - v * push.thickness);
                    float front = sideSign * safeAcos(dot(toSample, v));
                    float behind = sideSign * safeAcos(dot(back, v));
                    uint bits = sectors(min(front, behind), max(front, behind), n, cosN, total);
                    uint fresh = bits & ~covered;
                    if (gather && fresh != 0)
                    {
                        // Its surface's light towards the pixel: as it shows in the image if it faces the pixel
                        float3 sampleNormal = unpackNormal(uintImages[push.normal][q].x);
                        if (dot(sampleNormal, -toSample) > 0)
                        {
                            float3 radiance = textures[push.color].Load(int3(q, 0)).rgb;
                            light += projectedLength * total * float(countbits(fresh)) / 32 * radiance;
                        }
                    }
                    covered |= bits;
                }
            }
        }
        float h0 = -safeAcos(horizonCos1);
        float h1 = safeAcos(horizonCos0);
        h0 = n + clamp(h0 - n, -HALF_PI, HALF_PI);
        h1 = n + clamp(h1 - n, -HALF_PI, HALF_PI);
        whole += projectedLength * total;
        if (MODE == 1)
        {
            visible += projectedLength * total * (1 - float(countbits(covered)) / 32);
        }
        else
        {
            visible += projectedLength * (arc(h0, n, cosN) + arc(h1, n, cosN));
        }
        // The visible arc's mean direction (XeGTAO's closed form), in view space
        float t0 = (6 * sin(h0 - n) - sin(3 * h0 - n) + 6 * sin(h1 - n) - sin(3 * h1 - n) + 16 * sin(n)
            - 3 * (sin(h0 + n) + sin(h1 + n))) / 12;
        float t1 = (-cos(3 * h0 - n) - cos(3 * h1 - n) + 8 * cos(n) - 3 * (cos(h0 + n) + cos(h1 + n))) / 12;
        float3 local = float3(direction.xy * t0, -t1);
        bent += mul(rotationFromTo(float3(0, 0, -1), v), local) * projectedLength;
    }
    float ao = whole > 0 ? saturate(visible / whole) : 1;
    float3 bentNormal = dot(bent, bent) > 1.0e-12 ? normalize(bent) : normal;
    images[push.ao][id.xy] = float4(bentNormal, ao);
    images[push.bounce][id.xy] = float4(whole > 0 ? light / whole : 0, 0);
}

/** A hash of `x`: 32 well-mixed bits (Jarzynski and Olano's PCG). */
uint hash(uint x)
{
    uint state = x * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

/** A unit tangent and bitangent around unit `n` (Duff et al. 2017). */
void basisAround(float3 n, out float3 t, out float3 b)
{
    float sign = n.z >= 0 ? 1.0 : -1.0;
    float a = -1.0 / (sign + n.z);
    float c = n.x * n.y * a;
    t = float3(1 + sign * n.x * n.x * a, sign * c, -sign * n.x);
    b = float3(c, sign + n.y * n.y * a, -n.y);
}

/** Ray-traced occlusion (RAYS): cosine-distributed rays of the radius's length from each pixel's surface. */
[shader("compute")]
[numthreads(8, 8, 1)]
void raysMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    float z = sceneDepth(int2(id.xy));
    if (z <= 0)
    {
        writeNone(id.xy);
        return;
    }
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    float3 position = unproject(float2(id.xy) + 0.5, z, size);
    float3 viewNormal = unpackNormal(uintImages[push.normal][id.xy].x);
    float3 n = fromView(axes, viewNormal);
    float3 t;
    float3 b;
    basisAround(n, t, b);
    float distance = length(position);
    // Off the surface by a part of the distance (the traced detail level lies within a pixel of the drawn one)
    float3 origin = position + push.offset + n * max(push.rayOffset * distance, 1.0e-4);
    RayScene* scene = (RayScene*)push.rays;
    uint rays = max(push.steps, 1u);
    uint seed = hash(id.x * 1973u + id.y * 9277u);
    // A Cranley-Patterson rotation of the R2 sequence per pixel, stepped by the frame
    float2 rotation = float2(float(hash(seed) >> 8), float(hash(seed + 1u) >> 8)) * (1.0 / 16777216.0);
    float visible = 0;
    float3 bent = 0;
    float hitDistance = 0;
    uint hits = 0;
    for (uint k = 0; k < rays; k++)
    {
        float index = float(push.frame * rays + k);
        float2 u = frac(rotation + index * float2(0.7548776662, 0.5698402910));
        float r = sqrt(u.x);
        float phi = 2 * PI * u.y;
        float3 d = r * cos(phi) * t + r * sin(phi) * b + sqrt(max(0.0, 1 - u.x)) * n;
        RayHit hit = nearest(scene, origin, d, 0, push.radius);
        if (hit.hit)
        {
            hitDistance += hit.t;
            hits++;
        }
        else
        {
            visible += 1;
            bent += d;
        }
    }
    float ao = visible / float(rays);
    float3 bentNormal = dot(bent, bent) > 1.0e-12 ? normalize(toView(axes, bent)) : viewNormal;
    images[push.ao][id.xy] = float4(bentNormal, ao);
    images[push.bounce][id.xy] = float4(hits > 0 ? hitDistance / float(hits) : push.radius, 0, 0, 0);
}

/** The solid angle where two cones (half angles' cosines, the cosine between their axes) meet (Oat, Sander 2007). */
float capsIntersection(float cos1, float cos2, float cosBetween)
{
    float r1 = safeAcos(cos1);
    float r2 = safeAcos(cos2);
    float d = safeAcos(cosBetween);
    float smaller = 2 * PI * (1 - max(cos1, cos2));
    if (min(r1, r2) <= max(r1, r2) - d)
    {
        return smaller; // one inside the other
    }
    if (r1 + r2 <= d)
    {
        return 0;
    }
    float delta = abs(r1 - r2);
    float x = 1 - saturate((d - delta) / max(r1 + r2 - delta, 1.0e-6));
    return smaller * smoothstep(0.0, 1.0, x);
}

/** The cosine of the half angle of a GGX lobe of perceptual `roughness`, as a Phong lobe's (Uludag, GPU Pro 5). */
float lobeCosine(float roughness)
{
    float a = roughness * roughness;
    float power = 2 / max(a * a, 1.0e-6) - 2;
    return pow(0.244, 1 / (power + 1));
}

/**
 * The occlusion replacing what shading added: the diffuse light around the normal darkened (and BITMASK's bounce
 * added) by the diffuse weight, the environment's reflection dimmed by the specular occlusion by the reflectance.
 */
[shader("compute")]
[numthreads(8, 8, 1)]
void compositeMain(uint3 id : SV_DispatchThreadID)
{
    float2 size = float2(unpackSize(push.size));
    if (any(float2(id.xy) >= size))
    {
        return;
    }
    float z = sceneDepth(int2(id.xy));
    float4 weight = images[push.diffuse][id.xy];
    if (z <= 0 || weight.a <= 0)
    {
        images[push.occlusion][id.xy] = float4(1, 0, 0, 0);
        return;
    }
    float3x3 axes = viewAxes(push.view[0].viewProjection);
    float3 position = unproject(float2(id.xy) + 0.5, z, size);
    float3 toViewer = normalize(-position);
    float3 n = fromView(axes, unpackNormal(uintImages[push.normal][id.xy].x));
    float4 signal = images[push.ao][id.xy];
    float ao = saturate(signal.a);
    float3 bounce = (push.flags & AO_BOUNCE) != 0 ? max(images[push.bounce][id.xy].rgb, 0.0) : float3(0);
    float3 occlusion = ao;
    if ((push.flags & AO_MULTI_BOUNCE) != 0)
    {
        // Interreflections between the occluders, fitted by albedo (Jimenez et al. 2016): the diffuse weight's color
        float3 albedo = weight.rgb;
        float3 a = 2.0404 * albedo - 0.3324;
        float3 b = -4.7951 * albedo + 0.6417;
        float3 c = 2.7552 * albedo + 0.6903;
        occlusion = max(ao, ((ao * a + b) * ao + c) * ao);
    }
    float3 diffuseLight = INDIRECT != 0 ? diffuseLightAt(push.indirect, n, position, toViewer)
        : SKY_IRRADIANCE / PI;
    float3 color = images[push.color][id.xy].rgb;
    float3 delta = weight.rgb * ((occlusion - 1) * diffuseLight + bounce);
    float specular = 1;
    float4 material = images[push.material][id.xy];
    float3 reflectance = images[push.reflectance][id.xy].rgb;
    if ((push.flags & AO_SPECULAR) != 0 && any(reflectance > 0))
    {
        // The visible directions as a cone around the bent normal (a cosine-weighted cone holding the occlusion),
        // against the GGX lobe's cone around the mirror direction
        float3 bentNormal = dot(signal.xyz, signal.xyz) > 0.25 ? normalize(fromView(axes, signal.xyz)) : n;
        float3 r = reflect(-toViewer, n);
        // relative to what the open hemisphere around it leaves of the lobe (which the split sum already takes as
        // seen): an unoccluded surface keeps all of it
        float cosVisible = sqrt(saturate(1 - ao));
        float cosLobe = lobeCosine(material.a);
        float open = capsIntersection(0, cosLobe, dot(bentNormal, r));
        specular = open > 1.0e-6 ? saturate(capsIntersection(cosVisible, cosLobe, dot(bentNormal, r)) / open) : 1;
        float3 environment = INDIRECT != 0 ? specularLightAt(push.indirect, r, material.a, position, n, toViewer)
            : SKY_REFLECTED;
        delta -= reflectance * (1 - specular) * environment;
    }
    images[push.occlusion][id.xy] = float4(specular, 0, 0, 0);
    if ((push.flags & AO_SHOW_AO) != 0)
    {
        images[push.color][id.xy] = float4(ao, ao, ao, 1);
        return;
    }
    if ((push.flags & AO_SHOW_SPECULAR) != 0)
    {
        images[push.color][id.xy] = float4(specular, specular, specular, 1);
        return;
    }
    if ((push.flags & AO_SHOW_BOUNCE) != 0)
    {
        images[push.color][id.xy] = float4(bounce, 1);
        return;
    }
    images[push.color][id.xy] = float4(color + delta, 1);
}
