// The device's bindless set (engine-vulkan's Bindless): every sampled and storage image in one array each, indexed by
// slot numbers shaders take in their push constants.
module bindless;

[[vk::binding(0, 0)]]
public Texture2D<float4> textures[];

[[vk::binding(1, 0)]]
public SamplerState samplers[4];

[[vk::binding(2, 0)]]
public RWTexture2D<float4> images[];

// The same storage images, for those of unsigned integer formats (e.g. the visibility buffer's IDs).
// Two views of one binding, on purpose: each image is read through the one matching its format (the build allows
// the overlap: render/build.gradle.kts).
[[vk::binding(2, 0)]]
public RWTexture2D<uint2> uintImages[];

/** Sampler slots. */
public static const uint LINEAR_CLAMP = 0;
public static const uint NEAREST_CLAMP = 1;
public static const uint LINEAR_REPEAT = 2;
public static const uint MATERIAL = 3;      // trilinear, anisotropic, repeating
