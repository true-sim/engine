// The built-in attributes over life (E24): the size and color from birth scaled by a curve and a gradient over the
// particle's life, after its motion. As scene-effects' CpuSimulation (Over).
module overlife;

import particles.particle;

/** The size from birth times the curve. */
[ParticleModule("over.size")]
[BuiltInTwin]
public struct SizeOverLife
{
    public ModuleCurve curve = 1;

    public void over(inout ParticleStep s, Particle p)
    {
        s.size *= curve;
        s.sized = true;
    }
};

/** The color from birth times the gradient. */
[ParticleModule("over.color")]
[BuiltInTwin]
public struct ColorOverLife
{
    public ModuleGradient gradient = float4(1, 1, 1, 1);

    public void over(inout ParticleStep s, Particle p)
    {
        s.color *= gradient;
        s.colored = true;
    }
};
