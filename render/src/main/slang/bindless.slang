// The device's bindless set (engine-vulkan's Bindless): every sampled and storage image in one array each, indexed by
// slot numbers shaders take in their push constants. A slot that may differ between neighbouring invocations (per pixel
// or per light: materials, shadow maps) must go through NonUniformResourceIndex, or a wave reads one lane's image.
module bindless;

/** A struct the host writes into buffers that shaders read through pointers: the build generates its layout as Java. */
[__AttributeUsage(_AttributeTargets.Struct)]
public struct HostLayoutAttribute {};

[[vk::binding(0, 0)]]
public Texture2D<float4> textures[];

// The same sampled images, for cubes (an environment's) and for 2D arrays (a cube's level, its faces).
[[vk::binding(0, 0)]]
public TextureCube<float4> cubes[];

[[vk::binding(0, 0)]]
public Texture2DArray<float4> textureArrays[];

// And for 3D textures (Textures.volume and 3D dynamic textures: vector fields, noise volumes).
[[vk::binding(0, 0)]]
public Texture3D<float4> volumes[];

[[vk::binding(1, 0)]]
public SamplerState samplers[21];

[[vk::binding(2, 0)]]
public RWTexture2D<float4> images[];

// The same storage images, for those of unsigned integer formats (e.g. the visibility buffer's IDs).
// Two views of one binding, on purpose: each image is read through the one matching its format (the build allows
// the overlap: render/build.gradle.kts). Its format is unknown, as the float images' are: without the attribute Slang
// declares Rg32ui, which every R32_UINT image (the normals, the IDs) does not match, and accesses through a view of
// another format are undefined for the whole image (GPU-assisted validation reports each).
[[vk::binding(2, 0)]]
[[vk::image_format("unknown")]]
public RWTexture2D<uint2> uintImages[];

// The same storage images, for 2D arrays: a cube's level, its six faces as layers.
[[vk::binding(2, 0)]]
public RWTexture2DArray<float4> imageArrays[];

// The same storage images, for 3D ones (dynamic 3D textures GPU passes write: Textures.dynamic).
[[vk::binding(2, 0)]]
public RWTexture3D<float4> volumeImages[];

/**
 * Whether a pointer the host handed over names data (is given). For none (absent) the host hands
 * VulkanDevice.absentAddress(), a buffer of zeros with its lowest bit set, never 0: lavapipe performs loads in branches
 * no invocation takes and crashed on address 0 there (docs/pitfalls.md). A pointer made null in a shader is not given.
 */
public bool given<T>(T* p)
{
    uint64_t a = (uint64_t)p;
    return a != 0 && (a & 1) == 0;
}

/** Sampler slots. */
public static const uint LINEAR_CLAMP = 0;
public static const uint NEAREST_CLAMP = 1;
public static const uint LINEAR_REPEAT = 2;
public static const uint MATERIAL = 3;      // material samplers from here: engine-render's TextureSampler.index()
public static const uint MATERIAL_CLAMP = 7;
public static const uint MATERIAL_MIRROR = 11;
public static const uint MATERIAL_NEAREST = 12;
