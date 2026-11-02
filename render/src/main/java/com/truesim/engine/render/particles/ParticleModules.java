package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.effects.ModuleRegistry;
import com.truesim.scene.effects.ModuleType;
import java.util.List;
import java.util.Objects;

/**
 * A game's own particle modules (effects step 20), registered as material types are: Slang structs implementing
 * {@code IParticleModule} (particles/particle.slang), dispatched by a kernel variant the game compiles, whose entry file
 * defines {@code PARTICLE_MODULES} and a {@code ParticleModules} type calling each module by its index, then includes
 * particles/simulate.slang. Module {@code i} of {@link #types()} is opcode {@code OP_PLUGIN + i}; its fields (at most
 * {@code PLUGIN_FIELDS}, floats) reach it evaluated, in alphabetical order of their names, as built-in modules' do.
 * Effects naming them are built against {@link #registry()}; on the CPU they need a Java twin (a CPU target in their
 * type, run by the game's own simulator), else they are GPU only.
 *
 * <pre>{@code
 * // gust.slang (the game's): [shader] entry points come from the include
 * #define PARTICLE_MODULES
 * import particle;
 * struct Gust : IParticleModule { static void update(inout Particle p, ParticleModuleInput in) { ... } };
 * struct ParticleModules { static void update(uint i, inout Particle p, ParticleModuleInput in) { Gust.update(p, in); }
 *                          static void init(uint i, inout Particle p, ParticleModuleInput in) { } };
 * #include "particles/simulate.slang"
 *
 * ParticleModules gust = new ParticleModules("shaders/gust.spv", List.of(GUST));    // GUST: its ModuleType
 * particles.modules(gust);
 * EffectData leaves = EffectData.builder().emitter(...).build(gust.registry());
 * }</pre>
 *
 * @param shader the kernel variant: a resource path as {@code ShaderModule.load} takes, with the simulation's entry points
 * @param types the modules it adds, in the order its {@code ParticleModules} dispatches them
 */
public record ParticleModules(String shader, List<ModuleType> types) {

    /** The most float fields a plugin module reads. */
    public static final int MAX_FIELDS = com.truesim.engine.render.layout.ParticleConstants.PLUGIN_FIELDS;

    @AllowAllocation("set up once")
    public ParticleModules {
        Objects.requireNonNull(shader, "shader");
        types = List.copyOf(types);
        for (ModuleType type : types) {
            if (type.fields().size() > MAX_FIELDS) {
                throw new IllegalArgumentException(type.name() + " has more than " + MAX_FIELDS + " fields");
            }
        }
    }

    /** The built-in modules and these: what effects naming them are built against. */
    @AllowAllocation("set up once")
    public ModuleRegistry registry() {
        return ModuleRegistry.BUILT_IN.with(types.toArray(ModuleType[]::new));
    }

    /** The index of the module called {@code name}, or -1 if it is none of these. */
    int indexOf(String name) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
