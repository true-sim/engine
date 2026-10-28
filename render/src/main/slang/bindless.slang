// The device's bindless set (engine-vulkan's Bindless): every sampled and storage image in one array each, indexed by
// slot numbers shaders take in their push constants.
module bindless;

[[vk::binding(0, 0)]]
public Texture2D<float4> textures[];

[[vk::binding(1, 0)]]
public SamplerState samplers[3];

[[vk::binding(2, 0)]]
public RWTexture2D<float4> images[];

/** Sampler slots. */
public static const uint LINEAR_CLAMP = 0;
public static const uint NEAREST_CLAMP = 1;
public static const uint LINEAR_REPEAT = 2;
